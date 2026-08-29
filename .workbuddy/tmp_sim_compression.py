# -*- coding: utf-8 -*-
"""
忠实回放 eon-agent 真实 transcript（含多次 user run 边界），模拟：
  上下文水位 / 三级压缩触发 / 预算消耗 / 上下文构成
目的：验证压缩阈值是否合理、预算是否先于压缩耗尽。
"""
import json, io, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TRANSCRIPT = os.path.join(ROOT, "data", "eon_20260829_141027_d59fd3", "transcript.jsonl")
SYS_PROMPT = os.path.join(ROOT, "src", "main", "resources", "prompts", "system_prompt.md")

CTX_MAX = 200_000
SNIP_KEEP_CHARS = 4000
TAIL_GUARD_TURNS = 3
BUDGET_MAX = 2_000_000
BUDGET_HARD_GRACE = 3
MAX_STEPS = 100
SNIP_NOTICE = "\n... [中间内容已省略。此为截断后的摘要]"
PRUNE_TEXT = "[旧工具结果内容已清除]"

# ── 无损卸载（offload）─────────────────────────────────────────────
# 针对 AI 消息里的 write/edit 参数块：内容已由工具落盘，磁盘上有完整副本，
# 替换为"摘要 + artifact 引用"属【零信息损失】，因此不受"快满才压缩"的约束，
# 可以在任意低水位触发。对应 ToolExecutor.summarizeArgs() 已有的输出格式。
OFFLOAD_MIN_CHARS = 2000      # 参数块超过此值才值得卸载
OFFLOAD_TEXT = ('{arguments: "{path: \\"…\\"}"} '
                '[工具调用参数已卸载：完整内容已落盘，可用 read_file 读取该路径]')

# 真实日志锚点（用户提供的片段）
ANCHORS = {
    13: (47, 56_672, 612_937),
    14: (49, 53_424, 670_638),
    15: (51, 54_525, 728_737),
}

import tiktoken
ENC = tiktoken.get_encoding("o200k_base")
def ntok(s): return len(ENC.encode(s, disallowed_special=()))


def msg_text(o):
    t = o.get("content") or ""
    if o.get("toolCalls"):
        for tc in o["toolCalls"]:
            t += json.dumps(tc.get("arguments"), ensure_ascii=False)
    return t


def snip_text(n):
    return "x" * SNIP_KEEP_CHARS + SNIP_NOTICE


# 每次 LLM 调用额外付出的 tool schema（9 内置 + MCP novel 工具）token，
# 从真实日志反推：本轮+tok − 输入上下文 − 输出 ≈ 2000~3500，取 3000。
TOOL_SCHEMA_OVERHEAD = 3000
# 输出预留：llm.max_tokens=12000，向模型承诺的响应空间，同样占用窗口。
OUTPUT_RESERVE = 12000

# ── 水位口径（本次改造修复的关键一项）─────────────────────────────
# 旧实现 ContextBuilder.estimateTokens() 只统计 transcript，漏算上面两项，
# 水位读数系统性偏低 → 压缩迟迟不触发 → 预算先耗尽。
# 新实现 ContextMetrics.waterLevel() 已补齐。置 False 可复现旧口径做对照。
WATER_INCLUDES_OVERHEAD = True

# ── 阶段4 预算感知 ────────────────────────────────────────────────
ARTIFACT_THRESHOLD = SNIP_KEEP_CHARS * 3        # 12000 字符，超过则入站即落盘
COLLAPSE_MIN_CHARS = 200
COLLAPSE_TEXT = "[旧工具结果已折叠为引用。完整内容: artifact://art_x，可用 read_file 读取]"
BUDGET_AWARE_MIN_TURNS = 8.0


class Engine:
    def __init__(self, th, summarize_turns, also_ai_args=False,
                 offload="none", offload_pct=0.40, water_overhead=True):
        self.th, self.st = th, summarize_turns
        self.water_overhead = water_overhead
        self.also_ai_args = also_ai_args
        self.offload = offload          # none | eager（回填即卸载）| lazy（达水位才卸载）
        self.offload_pct = offload_pct
        self.msgs = []          # {kind, tok, char, id, tool, is_args}
        self.snipped, self.pruned = set(), set()
        self.summarized = 0
        self.offloaded = 0
        self.offload_freed = 0
        self.collapsed = 0
        self.last_compress_turn = 0
        self.turn = 0
        self.budget = 0
        self.events = []

    # ── 消息视图 ──
    def add(self, o):
        txt = msg_text(o)
        args = json.dumps([tc.get("arguments") for tc in (o.get("toolCalls") or [])], ensure_ascii=False) \
            if o.get("toolCalls") else ""
        body = o.get("content") or ""
        self.msgs.append({
            "kind": o["type"], "id": o.get("toolCallId") or f"a{len(self.msgs)}",
            "tool": o.get("toolName"), "text": txt, "body": body,
            "tok": ntok(txt), "char": len(txt), "orig_char": len(txt),
            # 拆分记录：卸载参数块时只扣掉 args 部分，保留正文
            "body_tok": ntok(body),
            "args_tok": ntok(args) if args else 0,
            "args_chars": len(args),
        })

    def toks(self): return sum(m["tok"] for m in self.msgs)

    def tail_start(self): return max(0, len(self.msgs) - TAIL_GUARD_TURNS * 2 - 2)

    # ── 水位 / 预算投影（对齐改造后的 ContextMetrics）──────────────
    def overhead(self):
        return (TOOL_SCHEMA_OVERHEAD + OUTPUT_RESERVE) if self.water_overhead else 0

    def water(self, sys_tok):
        """水位 = (transcript + 锚点 + 工具 schema + 输出预留) / 窗口。"""
        return min(1.0, (self.toks() + sys_tok + self.overhead()) / CTX_MAX)

    def projected_turns(self, sys_tok):
        """预算投影：按当前单轮成本，剩余预算还能跑几轮。"""
        per = self.toks() + sys_tok + TOOL_SCHEMA_OVERHEAD + OUTPUT_RESERVE
        if per <= 0:
            return float("inf")
        return max(0, BUDGET_MAX - self.budget) / per

    def compress(self, level):
        ts = self.tail_start()
        out = []
        if level >= self.th["snip"]:
            n = before = after = 0
            for i in range(min(ts, len(self.msgs))):
                m = self.msgs[i]
                if m["id"] in self.snipped: continue
                if m["kind"] == "tool":
                    if m["char"] <= SNIP_KEEP_CHARS: continue
                elif self.also_ai_args and m["kind"] == "ai":
                    if m["args_chars"] <= SNIP_KEEP_CHARS: continue
                else:
                    continue
                before += m["char"]
                m["text"] = snip_text(m["char"]); m["char"] = len(m["text"])
                m["tok"] = ntok(m["text"]); after += m["char"]
                m["args_chars"] = 0
                self.snipped.add(m["id"]); n += 1
            if n: out.append(f"Snip 截短{n}个({before}->{after}字符)")
        if level >= self.th["prune"]:
            n = 0
            for i in range(min(ts, len(self.msgs))):
                m = self.msgs[i]
                if m["id"] in self.pruned: continue
                if m["kind"] == "tool":
                    pass
                elif self.also_ai_args and m["kind"] == "ai":
                    if m["args_chars"] == 0 and m["char"] < 200: continue
                else:
                    continue
                m["text"] = PRUNE_TEXT; m["char"] = len(PRUNE_TEXT); m["tok"] = ntok(PRUNE_TEXT)
                m["args_chars"] = 0
                self.pruned.add(m["id"]); n += 1
            if n: out.append(f"Prune×{n}")
        if level >= self.th["summarize"] and ts > 0:
            n = ts; del self.msgs[:n]
            self.msgs.insert(0, {"kind": "summary", "id": "sum", "tool": None,
                                 "text": "S" * 3000, "tok": ntok("S" * 3000),
                                 "char": 3000, "args_chars": 0})
            self.summarized += n
            out.append(f"Summarize 删除{n}条")
        return " | ".join(out)

    def offload_args(self):
        """无损卸载：把 AI 消息里超阈值的工具参数块替换为「摘要 + 磁盘引用」。

        与 Snip/Prune 的本质区别：write/edit 的内容已由工具落盘，磁盘上存在
        完整副本，模型需要时可 read_file 取回 —— 信息零损失，因此不受
        「上下文快满才压缩」的约束，可以早触发甚至回填即触发。
        尾部保护区内的消息不动（模型刚写完，可能还要引用）。
        """
        ts = self.tail_start()
        n = freed = 0
        for i in range(min(ts, len(self.msgs))):
            m = self.msgs[i]
            if m["kind"] != "ai" or m["args_chars"] <= OFFLOAD_MIN_CHARS:
                continue
            freed += m["args_chars"]
            m["text"] = m["body"] + OFFLOAD_TEXT
            m["char"] = len(m["text"])
            m["tok"] = m["body_tok"] + ntok(OFFLOAD_TEXT)
            m["args_chars"] = 0
            n += 1
        self.offloaded += n
        self.offload_freed += freed
        return n, freed

    def collapse_spilled(self):
        """引用折叠（阶段4）：已落盘的块折叠成一行引用，零信息损失。

        触发判据是【预算投影】而不是水位——这是本次改造中唯一改变
        "何时开始处置"的规则。无损操作不受"快满才压"的约束。
        """
        ts = self.tail_start()
        n = freed = 0
        for i in range(min(ts, len(self.msgs))):
            m = self.msgs[i]
            # 只有入站时超过落盘阈值的块才有磁盘副本（refId）
            if m["kind"] != "tool" or m["orig_char"] <= ARTIFACT_THRESHOLD:
                continue
            if m["char"] <= COLLAPSE_MIN_CHARS:
                continue
            freed += m["char"]
            m["text"] = COLLAPSE_TEXT
            m["char"] = len(COLLAPSE_TEXT)
            m["tok"] = ntok(COLLAPSE_TEXT)
            n += 1
        self.collapsed += n
        return n, freed

    def composition(self):
        c = {"ai_args": 0, "tool_result": 0, "other": 0}
        for m in self.msgs:
            if m["kind"] == "ai" and m["args_chars"] > 500:
                c["ai_args"] += m["tok"]
            elif m["kind"] == "tool":
                c["tool_result"] += m["tok"]
            else:
                c["other"] += m["tok"]
        return c


def replay(rows, th, summarize_turns, sys_tok, extend_to=None, budget0=0,
           also_ai_args=False, offload="none", offload_pct=0.40,
           budget_aware=False, water_overhead=True):
    eng = Engine(th, summarize_turns, also_ai_args, offload, offload_pct, water_overhead)
    eng.budget = budget0
    stats = {"snip": 0, "prune": 0, "summarize": 0, "offload": 0, "collapse": 0}
    log_rows, snip_log = [], []
    hard_at = None

    # 把 transcript 切成 run 段，忠实重置 turn / lastTurnCompressed
    i = 0
    while i < len(rows):
        o = rows[i]
        if o["type"] == "user":
            eng.turn = 0
            eng.last_compress_turn = 0
            eng.add(o)
            i += 1
            continue
        if o["type"] != "ai":
            i += 1
            continue

        if hard_at is not None and len(log_rows) > hard_at + BUDGET_HARD_GRACE:
            break
        if eng.turn >= MAX_STEPS:
            break

        eng.turn += 1
        n_tools = len(o.get("toolCalls") or [])

        # ── PreModel ──
        sent = eng.toks() + sys_tok
        water = eng.water(sys_tok)          # 新口径：含工具 schema 与输出预留
        if eng.budget / BUDGET_MAX >= th["hard"] and hard_at is None:
            hard_at = eng.turn

        # 无损卸载（lazy 模式）：达水位才批量卸载，无损故可用较低水位
        if eng.offload == "lazy" and water >= eng.offload_pct:
            k, _ = eng.offload_args()
            stats["offload"] += k
            sent = eng.toks() + sys_tok
            water = eng.water(sys_tok)

        # 阶段4 预算感知：判据是投影剩余轮次，不是水位。无损，故先于有损阶梯执行。
        if budget_aware and eng.projected_turns(sys_tok) < BUDGET_AWARE_MIN_TURNS:
            k, _ = eng.collapse_spilled()
            stats["collapse"] += k
            if k:
                sent = eng.toks() + sys_tok
                water = eng.water(sys_tok)

        since = eng.turn - eng.last_compress_turn
        turn_trig = since >= summarize_turns and len(eng.msgs) > TAIL_GUARD_TURNS * 2 + 2
        water_trig = water >= th["snip"]
        stage = ""
        if water_trig or turn_trig:
            lvl = water if water_trig else th["snip"]
            stage = eng.compress(lvl)
            stats["snip"] += stage.count("Snip"); stats["prune"] += stage.count("Prune")
            stats["summarize"] += stage.count("Summarize")
            eng.last_compress_turn = eng.turn
            if "Snip 截短" in stage:
                snip_log.append((eng.turn, stage))

        sent = eng.toks() + sys_tok
        water = eng.water(sys_tok)
        out_tok = ntok(msg_text(o))
        eng.budget += sent + out_tok + TOOL_SCHEMA_OVERHEAD

        log_rows.append({
            "turn": eng.turn, "n": len(eng.msgs), "sent": sent, "water": water,
            "budget": eng.budget, "bratio": eng.budget / BUDGET_MAX, "stage": stage,
            "comp": eng.composition(),
        })

        # ── 回填 ──
        eng.add(o)
        j = i + 1
        while j < len(rows) and rows[j]["type"] == "tool" and n_tools > 0:
            eng.add(rows[j]); n_tools -= 1; j += 1
        i = j

        # 无损卸载（eager 模式）：工具刚落盘、参数块刚进历史就立刻卸载。
        # 对 prompt cache 最友好——历史一步到位稳定下来，后续轮次都能命中。
        if eng.offload == "eager":
            k, _ = eng.offload_args()
            stats["offload"] += k

    return log_rows, stats, hard_at, snip_log


def main():
    rows = [json.loads(l) for l in io.open(TRANSCRIPT, encoding="utf-8") if l.strip()]
    sys_tok = ntok(io.open(SYS_PROMPT, encoding="utf-8").read())

    cur = dict(snip=0.65, prune=0.80, summarize=0.92, hard=1.0, soft=0.75)
    # 校准 budget0：使 run4 turn15 的预算累计命中真实值 728,737
    _, _, _, _ = replay(rows, cur, 7, sys_tok)
    base15 = [r for r in replay(rows, cur, 7, sys_tok)[0] if r["turn"] == 15 and r["n"] > 30][0]["budget"]
    B0 = ANCHORS[15][2] - base15
    print(f"[校准] 预算基线偏移 B0 = {B0:,} tok（前 3 次用户输入累计消耗）\n")

    log_rows, stats, hard_at, snip_log = replay(rows, cur, 7, sys_tok, budget0=B0)

    print("=" * 78)
    print("一、模型校验（与真实日志逐点对齐）")
    print("=" * 78)
    by_turn = {r["turn"]: r for r in log_rows if r["turn"] in ANCHORS}
    # run 4 的 turn 13/14/15 = 倒数第 21/20/19 条 log（因为前面还有 3 个 run）
    run4 = [r for r in log_rows if r["turn"] in ANCHORS and r["n"] > 30]
    print(f"  system_prompt = {sys_tok} tok")
    print(f"  {'':10}{'真实':>34}   {'模拟':>34}")
    for t in (13, 14, 15):
        cands = [r for r in log_rows if r["turn"] == t and r["n"] > 30]
        if not cands: continue
        r = cands[0]
        an, at, ab = ANCHORS[t]
        print(f"  turn {t:<5} {an:>5}条 {at:>8,}tok {ab:>9,}预算   "
              f"{r['n']:>5}条 {r['sent']:>8,}tok {r['budget']:>9,}预算")

    print("\n  真实 Snip 日志: [压缩] Snip: 截短 3 个工具结果 (27997 -> 12126 字符)")
    for t, s in snip_log:
        print(f"  模拟 Snip 日志: turn {t} → {s}")

    print("\n" + "=" * 78)
    print("二、上下文构成（谁在吃 token）")
    print("=" * 78)
    last = log_rows[-1]
    c = last["comp"]; tot = sum(c.values())
    print(f"  末轮 {last['n']} 条 / {last['sent']:,} tok")
    for k, label in (("ai_args", "AI 消息里的工具调用参数（如整份 HTML）"),
                     ("tool_result", "ToolExecutionResultMessage"),
                     ("other", "其他（用户消息 / AI 正文 / 系统提示）")):
        print(f"    {label:<40} {c[k]:>8,} tok  {c[k]/tot*100:>5.1f}%")
    print(f"\n  → Snip / Prune 只处理 ToolExecutionResultMessage，"
          f"占比 {c['ai_args']/tot*100:.0f}% 的 AI 参数块完全不受影响。")

    print("\n" + "=" * 78)
    print("三、压缩触发情况（真实 33 turn 全程）")
    print("=" * 78)
    print(f"  Snip {stats['snip']} 次 | Prune {stats['prune']} 次 | Summarize {stats['summarize']} 次")
    print(f"  峰值水位 {max(r['water'] for r in log_rows)*100:.1f}% | "
          f"末轮预算 {last['budget']:,} ({last['bratio']*100:.0f}%)")
    for r in log_rows[-6:]:
        print(f"    turn {r['turn']:>3}: {r['n']:>3} 条 | {r['sent']:>7,} tok | "
              f"水位 {r['water']*100:>5.1f}% | 预算 {r['bratio']*100:>5.1f}% | {r['stage'] or '-'}")

    print("\n" + "=" * 78)
    print("四、预算 vs 水位赛跑（外推同一个稳态周期至预算耗尽）")
    print("=" * 78)
    # 用真实 run4 的最后 9 个 turn 作为稳态周期，循环外推
    r4 = [r for r in log_rows if r["n"] > 30]
    cycle_rows = rows[rows.index(rows[0]):]  # 占位
    # 取 run4 尾部原始消息做周期复制
    last_user = max(i for i, o in enumerate(rows) if o["type"] == "user")
    tail = rows[last_user + 1:]
    # 一个周期 = 最后 3 组 (ai,tool) 共 6 条
    period = tail[-6:]
    ext = list(rows)
    for _ in range(30):
        ext.extend(period)

    CUR = dict(snip=.65, prune=.80, summarize=.92, hard=1.0, soft=.75)
    OLD = dict(snip=.30, prune=.45, summarize=.60, hard=1.0, soft=.75)
    print(f"  {'配置':<34}{'Offld':>6}{'Coll':>6}{'Snip':>6}{'Prune':>6}{'Summ':>6}"
          f"{'稳态上下文':>11}{'可跑turn':>9}{'末轮水位':>10}")
    print("  " + "-" * 94)
    for label, th, st, fix, off, opct, ba, wo in (
        ("① 旧度量 + 基线 65/80/92（改造前实测）",
         CUR, 7, False, "none", 0.40, False, False),
        ("② 新度量 + 基线 65/80/92（仅修水位口径）",
         CUR, 7, False, "none", 0.40, False, True),
        ("③ 旧建议 30/45/60% · 4轮（有损提前）",
         OLD, 4, False, "none", 0.40, False, True),
        ("④ 旧建议 + 有损压缩参数块",
         OLD, 4, True, "none", 0.40, False, True),
        ("★ ⑤ 阈值不变 + eager offload（阶段2）",
         CUR, 7, False, "eager", 0.40, False, True),
        ("★ ⑥ 阈值不变 + offload@40%（lazy）",
         CUR, 7, False, "lazy", 0.40, False, True),
        ("★ ⑦ ⑤ + 预算感知折叠（阶段4）← 最终实现",
         CUR, 7, False, "eager", 0.40, True, True),
    ):
        lr, st2, ha, _ = replay(ext, th, st, sys_tok, budget0=B0,
                                also_ai_args=fix, offload=off, offload_pct=opct,
                                budget_aware=ba, water_overhead=wo)
        inb = [r for r in lr if r["bratio"] <= 1.0] or lr[:1]
        L = inb[-1]
        steady = sum(r["sent"] for r in inb[-8:]) / 8
        star = "  ←" if label.startswith("★") else ""
        print(f"  {label:<34}{st2['offload']:>6}{st2['collapse']:>6}{st2['snip']:>6}"
              f"{st2['prune']:>6}{st2['summarize']:>6}{steady:>11,.0f}{len(inb):>9}"
              f"{L['water']*100:>9.1f}%{star}")
    print("\n  'Offld'/'Coll' = 无损处置次数（磁盘已有完整副本，信息零损失，可早触发）")
    print("  'Snip/Prune/Summ' = 有损压缩次数（会丢信息，应只在上下文快满时触发）")
    print("  '可跑turn' = 2,000,000 预算在硬停止前支撑的轮数；'稳态上下文' = 末 8 轮平均发送 token。")
    print("  ① vs ②：只修水位口径就带来提升——旧口径系统性低估水位，压缩被推迟。")

    print("\n" + "=" * 78)
    print("五、只用真实数据点的线性外推（不依赖任何外推周期假设）")
    print("=" * 78)
    # 锚点1 用真实日志值；锚点2 用模拟值并按实测低估比例校准
    p1s = [r for r in log_rows if r["turn"] == 15 and r["n"] > 30][0]
    CAL = ANCHORS[15][1] / p1s["sent"]          # 实测低估系数 ≈1.089
    w1, b1 = ANCHORS[15][1] / CTX_MAX, ANCHORS[15][2] / BUDGET_MAX
    p2 = log_rows[-1]
    w2, b2 = p2["water"] * CAL, p2["bratio"]
    dt = p2["turn"] - 15
    w_rate = (w2 - w1) / dt
    b_rate = (b2 - b1) / dt
    print(f"  校准系数 = {CAL:.3f}（模拟在 turn15 低估 {(CAL-1)*100:.1f}%，已代入）")
    print(f"  锚点1 turn15(真实): 水位 {w1*100:.1f}%  预算 {b1*100:.1f}%")
    print(f"  锚点2 turn{p2['turn']}(校准后): 水位 {w2*100:.1f}%  预算 {b2*100:.1f}%")
    print(f"  增速: 水位 +{w_rate*100:.2f} pp/turn | 预算 +{b_rate*100:.2f} pp/turn")
    b_end = p2["turn"] + (1.0 - b2) / b_rate
    print(f"\n  预算 100% 到达于 turn {b_end:.1f}  ← 硬停止（grace 3 轮后强制退出）")
    print(f"  {'阈值':<20}{'到达 turn':>10}{'相对预算耗尽':>14}   结论")
    for name, thv in (("Snip 65%", 0.65), ("Prune 80%", 0.80), ("Summarize 92%", 0.92)):
        t_hit = p2["turn"] + (thv - w2) / w_rate
        gap = t_hit - b_end
        verdict = "勉强赶上" if gap <= 0 else f"预算早 {gap:.1f} 轮耗尽 ✗"
        print(f"  {name:<20}{t_hit:>10.1f}{gap:>13.1f}   {verdict}")
    print(f"\n  预算耗尽那一刻的水位 = {(w2 + (b_end - p2['turn']) * w_rate) * 100:.1f}%")
    print("  注：本节是【不含压缩反馈】的线性外推，会高估增长速度——")
    print("      第四节的实际回放里 Snip/Offload 会把曲线压平，因此实际可跑轮数更高（39~51 轮）。")
    print("      本节的价值是给出上界悲观估计：即便完全不压缩，Prune/Summarize 也基本够不着。")

    print("\n" + "=" * 78)
    print("六、结论")
    print("=" * 78)
    print("  1. 下调阈值（③ 30/45/60%）是最差的正经选项：")
    print("       稳态 68,529（−27%），换来 8 Snip + 7 Prune + 1 Summarize 的信息损失，")
    print("       只多跑 4 轮（39→43）。在上下文还有大量空间时做有损压缩，得不偿失。")
    print("  2. 无损卸载（⑤）是唯一同时做到『更低稳态 + 更多轮数 + 零信息损失』的方案：")
    print("       稳态 94,369 → 58,423（−38%），可跑 39 → 51 轮，有损压缩仍是仅 4 次 Snip。")
    print("  3. eager（回填即卸载）优于 lazy（达水位才卸载）：26 次 vs 22 次，51 轮 vs 47 轮。")
    print("       无损操作没有『等快满再做』的理由——等水位意味着中间每轮都在重复付费。")
    print("  4. 阶段4 预算感知折叠在【本 transcript 上是空转的】（Coll = 0）：")
    print("       它针对的是已落盘的大工具结果（>12000 字符），而这份会话的大头是")
    print("       AI 参数块（72%），工具结果普遍没到落盘阈值。")
    print("       它的价值在 write 密集、单次读取大文件的会话里，属于防御性补充而非主收益。")


if __name__ == "__main__":
    main()
