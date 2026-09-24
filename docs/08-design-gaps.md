# 08 · 设计缺口与根治方向

> 本篇记录在源码层逐条验证过的设计缺口。**只记录、不改代码**——每条给出根因分析与根治方向，供后续重构时决策。
>
> 判定标准：不是"代码不够漂亮"，而是「声明的契约与实际行为不一致」或「防护看起来存在但实际不生效」。这两类问题最危险，因为读代码的人会基于错误的假设做决策。

## 严重度定义

| 级别 | 含义 |
|---|---|
| 🔴 高 | 会导致资源耗尽、数据静默腐蚀，或让读者对系统安全性产生错误判断 |
| 🟡 中 | 契约与实现不一致，新增代码时容易踩坑 |
| 🟢 低 | 死代码或不可观测，不影响正确性但增加维护负担 |

---

## 1. 🔴 工具派发层无超时兜底

**现象**：并行工具的结果收集没有 deadline。

`engine/exec/ToolCallDispatcher.java:90-103`：

```java
for (int j = 0; j < pendingIndices.size(); j++) {
    int idx = pendingIndices.get(j);
    ToolExecutionRequest req = requests.get(idx);
    try {
        results[idx] = futures.get(j).get();     // ← 无超时参数
    } catch (InterruptedException e) { ... }
    catch (ExecutionException e) { ... }
}
```

**影响面**：

- `tool-exec` 池是 `newFixedThreadPool(eon.tools.parallelism)`，**默认只有 4 个线程**（`:49-55`）。一个卡死的工具会永久占用一个线程，四次即耗尽整个进程的工具执行能力——注意这个池是 `@Component` 单例持有的，**跨会话共享**，所以一个会话的卡死工具会拖垮所有会话。
- run 线程会一直阻塞在 `future.get()` 上。中断机制是协作式的（只在派发返回后才查 `isInterrupted()`），且**从不 `cancel(true)`**，所以用户点"停止"也无法解开这个阻塞。
- 唯一能兜住的是 LLM 层的 300s 超时——但那管的是模型调用，不是工具执行。工具卡住时 SSE emitter 会在 1800s 后断开，而线程仍然挂着。

**当前的缓解**：超时全靠各工具自己实现（`download_file` 60s、`web_fetch` 30s、`web_search` 30s）。这个策略对内置工具成立，但对 **MCP 远程工具完全不成立**——`McpServerClient.invoke`（`tool/mcp/McpServerClient.java:70-87`）没有任何超时设置，一个不响应的 MCP 服务端就能永久挂住一个线程。

**根因**：超时被当作"工具自己的事"，但工具是第三方/远程的，无法保证都实现了超时。派发层作为唯一的收口点，反而没有兜底。

**根治方向**：在派发层建立统一的超时契约，而不是逐个工具补：

1. `futures.get(j).get(timeout, TimeUnit)` 加 deadline，超时视为工具失败（走 `syntheticError`），让熔断器接管——连续超时的工具会被自动封禁，这比无限等待好得多。
2. 超时时长按**权限或来源**分级配置（如内置工具 60s、MCP 工具 120s），而不是一个全局值。这需要在 `ToolDescriptor` 上增加超时元数据——正好与第 2 条缺口的根治方向（让 MCP 走同一条 descriptor 通路）合并做。
3. 超时后 `future.cancel(true)`，并让工具侧的阻塞操作可中断（`HttpClient` 的调用本身响应中断）。
4. 中断路径也应能打断等待：目前 `interrupt` 只设标志，可以让它同时对未完成的 future 调 `cancel(true)`。

**为什么不该只给 MCP 加超时**：那样会形成"内置工具有自己的超时、MCP 有派发层超时"两套语义，下次接入新的远程工具类型时又要再补一次。收口在派发层才是根上解决。

---

## 2. 🟡 MCP `permission` 配置解析后从未存储

**现象**：配置项存在、被解析、被打进日志，但不参与任何决策。

`tool/ToolService.java:44-61`：

```java
public int registerMcpTools(RemoteToolInvoker remoteTools, String permission) {
    ToolPermission perm = parsePermission(permission);        // :45 解析
    ...
    for (ToolSpecification spec : toolSpecs) {
        mcpToolSources.put(toolName, remoteTools);
        mcpToolSpecs.put(toolName, spec);
        log.info("远程工具已注册: {} [{}] 来自服务 '{}'", toolName, perm, ...);   // :56-57 perm 只在这里出现
    }
}
```

`perm` 是局部变量，从未写入任何字段。而权限查询走的是另一条路径（`:126-136`）：

```java
public ToolPermission getPermission(String name) {
    ToolDescriptor descriptor = tools.get(name);
    if (descriptor != null) return descriptor.getPermission();
    if (mcpToolSpecs.containsKey(name)) return ToolPermission.READONLY;   // ← 硬编码
    return null;
}
```

**影响面**：把 `application.yml:119` 的 `permission` 改成 `DESTRUCTIVE` 或 `RESTRICTED_WRITE`，运行时行为**完全不变**，只有启动日志里的那行文字会变。配置项是纯装饰性的。

**根因**：MCP 工具没有走 `ToolDescriptor` 通路。本地工具的权限存在 descriptor 上，MCP 工具的 spec 和 source 分别存在两个裸 map 里（`:21-22`），权限无处安放，于是 `getPermission` 用 `if` 分支按"来源"硬编码返回值。

**根治方向**：让 MCP 工具与本地工具**共用同一个 `ToolDescriptor` 抽象**。`ToolDescriptor` 的五个字段（name / description / permission / specification / executor）对 MCP 工具全都适用——`executor` 可以是一个把调用转发给 `RemoteToolInvoker` 的适配器。这样：

- `tools` 一个 map 就够了，`mcpToolSpecs` / `mcpToolSources` 可以删除
- `getPermission` 退化为一次 map 查找，`if` 分支消失
- 权限配置自然生效，无需任何额外代码
- 顺带解决 `execute` 的路由分支（`:95-124` 目前是"先查本地、再查 MCP、都不在则失败"三段式）

**注意**：合并后本地工具的白名单过滤会作用于 MCP 工具，而当前 javadoc（`:40-41`）明确写了「不受本地白名单限制」。这是个需要显式决策的语义变更——要么给 descriptor 加一个 `source` 标记让白名单只过滤本地工具，要么承认统一白名单更合理并更新文档。不要静默改变行为。

---

## 3. 🔴 审批门禁（GateHook）实际永不触发

**现象**：整条"破坏性操作需用户审批"的防护路径是休眠代码。

三个条件同时成立：

1. **没有任何内置工具声明 `DESTRUCTIVE`**。全仓库对 `ToolPermission` 的使用只有 4 处 `RESTRICTED_WRITE`（`WriteFileTool:76`、`DownloadFileTool:154`/`:158`、`TodoWriteTool:139`），其余全是 `READONLY`。
2. **MCP 工具的权限被硬编码为 `READONLY`**（第 2 条缺口）。
3. **即使有 DESTRUCTIVE 工具，默认也是自动批准**。`eon.tools.auto_approve_destructive` 默认 `true`（`config/AgentConfig.java:635`），且该键在 `application.yml` 中完全缺失；`GateHook:50-52` 在此情况下只记一条 WARN。

所以 `GateHook.beforeToolExecution`（`engine/hook/pretool/GateHook.java:44-60`）的循环体里，`if (!toolService.isDestructive(req.name())) continue;` 对所有工具都成立，永远走不到下面的分支。

**影响面**：这是最危险的一类缺口——**代码里存在一个看起来在保护用户的机制，实际上它从不运行**。`write` 可以覆盖任意工作区文件、`download_file` 可以往磁盘写 100MB，两者都是 `RESTRICTED_WRITE`，而 `RESTRICTED_WRITE` 这一级**没有任何代码消费它**（只在注册日志里出现，`ToolService:37`）。整个三级权限体系实际退化为两级：DESTRUCTIVE（不存在）和其他（不管）。

**根因**：权限分级是设计时预留的，但没有完成"给工具打标"和"让中间级产生作用"这两步。`RESTRICTED_WRITE` 定义了却没有消费者，说明这一级从一开始就没有落地方案。

**根治方向**：两条路，需要明确选一条，不要维持现状：

- **路线 A（补齐门禁）**：重新评估每个工具的权限。`write`（覆盖已有文件）和 `download_file`（写磁盘 + 消耗带宽）是否应该纳入审批？如果纳入，`GateHook` 需要支持 `RESTRICTED_WRITE` 档（不只是 DESTRUCTIVE），并把 `auto_approve_destructive` 显式写进 yml 让人能看见它。同时需要设计"审批"的实际交互——目前 `stop(GATE_REJECTED)` 是直接终止任务，用户批准后如何恢复并没有实现，`StopCategory.GATE_REJECTED` 的文案「需要用户审批」承诺了一个不存在的能力。
- **路线 B（承认不需要门禁）**：删除 `ToolPermission.DESTRUCTIVE`、`GateHook`、`auto_approve_destructive` 配置与 `GATE_REJECTED` 类别。防护改由已有的路径沙箱（`PathResolver`）承担，它确实生效。

路线 A 里"审批后恢复"这一点值得单独强调：`ask_question` 已经证明了阻塞等待用户输入的能力，门禁完全可以复用它——把 `stop` 换成一次 `ask_question` 式的阻塞确认，用户拒绝就把拒绝理由作为 tool_result 返回给模型。这比"终止任务让用户重新开始"体验好得多，也是真正兑现 `GATE_REJECTED` 文案承诺的做法。

---

## 4. 🟡 `HookResult.skip()` 的语义在四个阶段不一致

**现象**：`skip()` 的 javadoc 承诺的行为只在 PostModel 阶段成立。

`engine/hook/HookResult.java:25-28`：

```java
/** 跳过当前 Turn 的后续阶段，直接进入下一轮循环（不退出）。 */
public static HookResult skip() { return new HookResult(Action.SKIP, null, null); }
```

派发层**四个阶段都会为 skip 短路**（`engine/hook/HookDispatcher.java:20`、`:34`、`:49`、`:64` 都是 `if (result.isSkip() || result.isStop()) return result;`）——即后续钩子不再执行。

但引擎层**只有 PostModel 检查 `isSkip()`**：

| 阶段 | 引擎方法 | 检查了什么 |
|---|---|---|
| PreModel | `AgentEngine.prepareContext:297-304` | 只 `isStop()` |
| PostModel | `AgentEngine.firePostModelHooks:306-318` | `isContinue()` / `isSkip()` / `isStop()` 三态齐全 |
| PreTool | `AgentEngine.firePreToolHooks:320-327` | 只 `isStop()` |
| PostTool | `AgentEngine.firePostToolHooks:329-335` | 只 `isStop()` |

**影响面**：一个 PreModel/PreTool/PostTool 钩子返回 `skip()` 会产生**半生效**的行为——同阶段后续钩子被跳过（派发层短路了），但当前 Turn 照常继续（引擎忽略了 skip）。这比完全不生效更难排查：钩子链的执行顺序变了，但流程没变。

按 javadoc 写新钩子的人会踩这个坑，而且编译器和运行时都不会给出任何提示。

**根因**：`HookResult` 是四个阶段共用的返回类型，但 `SKIP` 的语义只对 PostModel 有意义（只有那里存在"已经拿到模型输出、但决定不执行工具"这个决策点）。用共享类型表达了阶段专属的语义。

**根治方向**：让类型系统承担约束，而不是靠文档提醒。两种做法：

- **收窄返回类型**（推荐）：`PostModelHook.afterModelCall` 返回一个支持三态的 `PostModelResult`，其余三个阶段返回只支持 `ok` / `stop` 的 `StageResult`。这样在 PreModel 钩子里写 `return skip()` **直接编译不过**。
- **统一语义**：让四个阶段的引擎方法都处理 `isSkip()`，并明确定义每个阶段的 skip 分别意味着什么（PreModel skip = 跳过本轮模型调用？PreTool skip = 不执行工具但保留 AI 消息？PostTool skip = ？）。这条路要先想清楚语义，否则只是把混乱从一处搬到四处。

第一种更符合"用类型表达约束"的取向：`skip` 本来就是 PostModel 独有的能力，让它在其他阶段无法被表达，比让它"存在但被忽略"诚实得多。

---

## 5. 🟡 熔断状态跨任务泄漏（`reset()` 是死代码）

**现象**：`ToolCircuitBreaker.reset()` 声明了用途但从未被调用。

`engine/guard/ToolCircuitBreaker.java:97-102`：

```java
/** 清空全部熔断状态，在每个任务开始时调用。 */
public void reset() {
    failureCount.clear();
    trippedCooldown.clear();
    log.debug("[ToolCircuitBreaker] 熔断状态已重置");
}
```

全仓库对 `reset` 与 `composition` 的搜索**只返回定义本身，零调用点**（已用 grep 确认）。

**影响面**：熔断器是 **session 级**对象（在 `runtime/cache/SessionScopeLoader.java:132-133` 构造，存于 `SessionScope`），而 `SessionScope` 由 Caffeine 缓存管理，idle TTL 30 分钟。所以：

- 任务 A 里某个工具连续失败 5 次被熔断
- 任务 A 结束，熔断状态留在 `SessionScope` 上
- 30 分钟内用户发起任务 B（同一会话）
- 任务 B 一开始那个工具就是熔断态，`ToolCallDispatcher:130-134` 直接返回合成失败，**模型连试都试不到**

冷却机制（`tickCooldown`）会在 3 轮后自动恢复，所以影响有边界；但任务 B 的前 3 轮里，一个可能已经恢复正常的工具是不可用的，且模型收到的是「工具 X 已熔断不可用」这种指向不明原因的消息。

`failureCount` 的泄漏更隐蔽：它不触发拦截，但任务 B 里该工具的**第一次失败就可能直接达到 warn 或 stop 阈值**（因为计数从任务 A 的残留值继续累加）。

**根因**：熔断器的作用域归属没有被明确决策。`LoopDetector` 和 `ProgressTracker` 都是 task 级（在 `TaskScope` 构造，`runtime/TaskScope.java:29-30`），熔断器却放在 session 级——但 `reset()` 的 javadoc「在每个任务开始时调用」暴露了原作者的意图其实是 task 级语义，只是实现时放错了位置，然后忘了补调用点。

**根治方向**：先决定语义，再改代码。两种选择都比"补一个 `reset()` 调用点"更诚实：

- **若熔断应为 task 级**（与另两个守卫一致，也符合 javadoc 意图）：把 `ToolCircuitBreaker` 从 `SessionScope` 下沉到 `TaskScope`，与 `LoopDetector` / `ProgressTracker` 并列构造。然后**删除 `reset()`**——对象随 task 丢弃，不需要重置方法。作用域即生命周期，比手动 reset 更不容易出错。
- **若熔断确应为 session 级**（认为"这个工具在这个会话里就是不靠谱"值得跨任务记忆）：删除 `reset()` 并修正 javadoc，同时在文档里写明这是有意的跨任务行为。可以考虑让冷却按**时间**而非轮数推进，因为跨任务时"轮"的连续性已经断了。

不要只是加一行 `reset()` 调用：那样代码看起来对了，但作用域归属的模糊性还在，下一个读代码的人仍要重新推理一遍。

---

## 6. 🟢 上下文度量不可观测（`composition()` 无调用方）

**现象**：算好了的度量没有出口。

`context/ContextMetrics.java:48-64` 的 `composition()` 产出可读的构成占比串：

```java
/** 构成分解的可读形式。 */
public String composition() {
    ...
    sb.append(e.getKey().name()).append(' ')
      .append(Math.round((double) e.getValue() / total * 100)).append('%');
    // → "TOOL_RESULT 45% | AI_TEXT 30% | TOOL_ARGS 15% | USER_INPUT 10%"
}
```

全仓库**零调用点**。整个 `ContextMetrics` 的实际消费者只有两处：

1. `CompressionPolicy.resolveLevel`（`context/policy/CompressionPolicy.java:83-86`）读 `waterLevel()` 判档位
2. `ContextCompressionHook`（`engine/hook/premodel/ContextCompressionHook.java:50-51`）在**命中档位时**记一条「水位 X% -> Y%」日志

**影响面**：

- 水位没命中档位时（绝大多数轮次）**什么都不记**，所以日志里看不到上下文的增长曲线
- `tokensByKind` 算了但没人读——每轮为四种 BlockKind 做 `EnumMap` 累加是纯开销
- token 估算用的是 gpt-4o 估算器而模型是 `mimo-v2.5`（`config/AgentBeans.java:147-150`），这个近似偏差**无法被观测和校准**，因为没有地方能看到估算值与实际用量的对比
- 压缩是不是太激进/太保守、哪一类块吃掉了上下文、水位为什么突然跳到 0.92——这些问题目前只能靠猜

**根因**：度量能力建好了，但没有接到任何出口（日志、事件、端点）。`engine.hook` 事件只表示"正在压缩"，不携带任何数值。

**根治方向**：给它一个出口，或者删掉它。

- **最小成本**：在 `ContextCompressionHook` 里把「水位 + composition」**每轮**记成 DEBUG 日志（不只命中档位时），生产上按需开 `logging.level`。这能立刻回答"上下文被什么吃掉了"。
- **更有价值**：新增一个诊断事件（如 `engine.context`）或在 `session.usage` 里附带水位与构成，让前端能画出上下文增长曲线。`session.usage` 已经在报 token 累计，加上水位是自然延伸。
- **顺便校准估算器**：`session.usage` 报的 `promptTokens` 是模型返回的**真实值**，而水位用的是**估算值**。把两者一起记下来就能量化估算偏差，进而决定要不要换成与 `mimo-v2.5` 更匹配的估算方式。
- 若判断这些度量确实不需要，则删除 `composition()` 与 `tokensByKind`，减少每轮的无用计算。

---

## 附：非缺陷但易误解的设计取舍

以下都是**有意为之**的设计，列出来是为了避免读者误判为 bug。

### `task().turnId()` 是任务级常量，不是每轮唯一

`runtime/TaskScope.java:28` 在构造时生成一次，整个任务的所有 SSE 事件都携带同一个 `turn_id`。真正的每轮标识是 `TurnScope.messageId`（`runtime/TurnScope.java:30`）。事件字段名 `turn_id` 有误导性——前端不能靠它区分轮次，要靠 `user.message` 事件切分（回放路径同理，`store/ledger/LedgerReplayer.java:46` 整次回放共用一个 `"replay_" + hex`）。

### LoopDetect 在门禁之前计数，且 warn 会丢弃整批

`engine/hook/postmodel/LoopDetectHook.java:42-59` 在 PostModel 阶段执行，早于 PreTool 的 `GateHook`。所以被门禁拦下的、或被前面钩子 skip 掉的调用**仍会累加循环计数**。命中 warn 时 `return skip()` 丢弃的是**整个 pendingToolCalls 批次**，不是仅违规的那一个——批次里其他合法调用也一起被丢。

### 工具 schema 用 220 token/个 的平坦估算

`engine/AgentEngine.java:50`、`:289-291`。不解析真实 schema，就是 `工具数 × 220`。工具越多误差越大，但胜在零成本且稳定。它会计入水位分子，所以注册大量 MCP 工具会实质压缩历史消息的可用空间。

### 畸形工具参数 JSON 降级为空 map

`engine/exec/ToolCallDispatcher.java:160-168` 解析失败只记 WARN 并返回 `Map.of()`。这是有意的：工具随后报出的「缺少 'xxx' 参数」是**语义化**错误，模型能据此自我纠正；而 JSON 语法错误往往会让模型重复同样的错误。

### `thinking` 落账本但永不进上下文窗口

`store/ledger/LedgerStore.java:138` 持久化它，`LedgerReplayer:88-90` 回放为 `AgentThinking` 事件给前端，但 `MessageBlockCodec.explode` 完全不读 `am.thinking()`——推理链不参与后续轮次的上下文。这既省 token，也避免模型被自己过往的推理带偏。

### 账本行永远保存原始消息

`store/ledger/LedgerStore.java:22-25`：磁盘账本 append-only 永不修改，入站管线的落盘与压缩只作用于内存 `ContextWindow`。这是"任何时刻都能重建未压缩历史"的基础，也是前端能看到完整历史而 LLM 窗口可能已压缩的原因。

### 前端看到的历史与 LLM 看到的不一致

承接上一条：`LedgerReplayer.replay` **始终从第 0 行**读，无视 `replayFromSeq` 水位线；而 `LedgerStore.loadAll(fromSeq)` 从水位线之后回放。两条路径互不相干，不一致是设计使然。

### 强制终止时客户端会收到两个 status 帧

`StopHandler.forceTerminate` 发 `session.status: terminated`，随后 `AgentEngine.completeExit` 又发 `session.status: idle("task_completed")`（`:241`）。前端应以**是否收到 `session.error`** 判断任务成败，不能只看最后一个 status。

### `summarize_max_output_chars` 恰好等于 `llm.max_tokens`

两者都是 12000。`application.yml:43-45` 的注释解释了为什么这是硬约束：超出会导致摘要被截断，而 `complete()` 丢弃 `finishReason` 不感知截断，**残缺摘要会被当成有效结果存下并在下一轮继续被压**——静默的信息腐蚀且自我放大。调整任一值时必须同步评估另一个。

### `RunContext.close()` 不释放会话状态机

`runtime/RunContext.java:59` 注释明确：「不释放会话状态机（由 SessionRegistry 负责）」。释放顺序在 `api/service/ChatServiceImpl.java:129-139` 的嵌套 finally 里：`interactions.cancel` → `indexStore.touch` → `registry.release` → `ctx.close()`。

---

## 相关篇章

- [01-engine.md](01-engine.md) — 缺口 4 涉及的引擎四阶段派发方法
- [03-hooks.md](03-hooks.md) — 缺口 3、4、5 涉及的钩子与守卫子系统
- [04-tools.md](04-tools.md) — 缺口 1、2、3 涉及的派发层、MCP 集成与权限模型
- [02-context.md](02-context.md) — 缺口 6 涉及的度量与压缩链路
- [06-persistence.md](06-persistence.md) — 缺口 5 涉及的 session 级作用域与缓存 TTL
- [07-configuration.md](07-configuration.md) — 缺口 1、3 涉及的配置键与默认值
