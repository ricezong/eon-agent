package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.LlmSupport;
import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.CompressionLevel;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.CompressionState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压缩策略特征测试。面向 apply() 公共签名锁定既有行为：
 * 档位判定边界（水位三档优先、轮数兜底）、处置范围与跳过条件、
 * SUMMARIZE 的先摘要后删除与失败降级。
 * <p>
 * 测试用 policy 实例把 tailGuardBlocks 设为 0，让保护区只覆盖锚点本身，
 * 便于构造小用例。生产默认值是 12（覆盖约 2-3 条完整消息）。
 */
class CompressionPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 可编程 LLM 桩：捕获请求，可返回固定摘要或抛错。 */
    static final class StubLlm implements LlmSupport {
        String reply = "这是测试摘要。";
        boolean fail;
        final List<List<ChatMessage>> captured = new ArrayList<>();

        @Override
        public String complete(List<ChatMessage> messages) {
            captured.add(messages);
            if (fail) throw new RuntimeException("LLM 不可用");
            return reply;
        }
    }

    // ═══════════════ 工具方法 ═════════════

    private static ContextBlock block(String id, BlockKind kind, String text) {
        return ContextBlock.builder()
                .id(id).kind(kind).groupId("g-" + id).ordinal(0)
                .toolName(kind == BlockKind.USER_INPUT || kind == BlockKind.AI_TEXT ? null : "tool")
                .toolCallId(kind == BlockKind.USER_INPUT || kind == BlockKind.AI_TEXT ? null : "call-" + id)
                .text(text)
                .build();
    }

    /** 水位 = transcript / 10000。 */
    private static ContextMetrics metricsAt(double water) {
        return new ContextMetrics((long) (water * 10000), 0, 0, 0, 10000, null);
    }

    /** 测试用 policy：tailGuardBlocks=0，保护区只覆盖锚点本身。 */
    private static CompressionPolicy policy(StubLlm llm) {
        AgentConfig.ContextConfig cfg = new AgentConfig.ContextConfig();
        cfg.getCompression().setTailGuardBlocks(0);
        return policy(llm, cfg);
    }

    private static CompressionPolicy policy(StubLlm llm, AgentConfig.ContextConfig cfg) {
        return new CompressionPolicy(cfg, JSON,
                new ContextSummarizer(llm, "/tmp/transcript.jsonl", cfg));
    }

    // ═══════════════ 档位判定边界 ═══════════════

    @Test
    @DisplayName("水位低于三档且非轮数倍数：不压缩")
    void belowAllThresholdsNoop() {
        ContextWindow window = new ContextWindow();
        window.addAll(List.of(block("r1", BlockKind.TOOL_RESULT, "x".repeat(5000))));
        CompressionLevel level = policy(new StubLlm())
                .apply(window, metricsAt(0.50), new CompressionState(), 3);
        assertThat(level).isEqualTo(CompressionLevel.NONE);
        assertThat(window.blocks().get(0).chars()).isEqualTo(5000);
    }

    @Test
    @DisplayName("水位恰好命中 SNIP 下限（>=）：执行 SNIP")
    void exactlyAtSnipThresholdApplies() {
        ContextWindow window = new ContextWindow();
        window.addAll(List.of(
                block("r1", BlockKind.TOOL_RESULT, "x".repeat(5000)),
                block("u9", BlockKind.USER_INPUT, "近期内容")));
        CompressionLevel level = policy(new StubLlm())
                .apply(window, metricsAt(0.65), new CompressionState(), 11);
        assertThat(level).isEqualTo(CompressionLevel.SNIP);
    }

    @Test
    @DisplayName("轮数兜底：非水位倍数轮（7 的倍数）执行 SNIP")
    void turnIntervalTriggersSnip() {
        ContextWindow window = new ContextWindow();
        window.addAll(List.of(
                block("r1", BlockKind.TOOL_RESULT, "x".repeat(5000)),
                block("u9", BlockKind.USER_INPUT, "近期内容")));
        CompressionLevel level = policy(new StubLlm())
                .apply(window, metricsAt(0.10), new CompressionState(), 7);
        assertThat(level).isEqualTo(CompressionLevel.SNIP);
    }

    @Test
    @DisplayName("水位优先于轮数：轮数倍数但水位在 PRUNE 区间，执行 PRUNE 而非 SNIP")
    void waterLevelOverridesTurnEntry() {
        ContextWindow window = new ContextWindow();
        ContextBlock recoverable = block("r1", BlockKind.TOOL_RESULT, "x".repeat(5000));
        recoverable.setRecoverable(true);
        recoverable.setRefId("art-1");
        window.addAll(List.of(recoverable, block("u9", BlockKind.USER_INPUT, "近期内容")));

        CompressionLevel level = policy(new StubLlm())
                .apply(window, metricsAt(0.85), new CompressionState(), 7);

        assertThat(level).isEqualTo(CompressionLevel.PRUNE);
        // 注：该结果块无配对调用块，修复配对时会被移出窗口，断言直接作用于块对象
        assertThat(recoverable.text()).contains("artifact://art-1");
    }

    // ═══════════════ SNIP 档行为 ═══════════════

    @Test
    @DisplayName("SNIP：保护区外的 TOOL_RESULT 与 AI_TEXT 头尾截断，保护区内的块不动")
    void snipTruncatesOldToolResultsAndAiText() {
        ContextWindow window = new ContextWindow();
        ContextBlock oldResult = block("r1", BlockKind.TOOL_RESULT, "x".repeat(5000));
        ContextBlock oldAi = block("a1", BlockKind.AI_TEXT, "y".repeat(5000));
        ContextBlock oldUser = block("u1", BlockKind.USER_INPUT, "老问题".repeat(100));
        ContextBlock newResult = block("r9", BlockKind.TOOL_RESULT, "z".repeat(5000));
        // 顺序：[oldResult, oldAi, oldUser, newResult]，lastUserIndex=2，protectedFrom=2
        window.addAll(List.of(oldResult, oldAi, oldUser, newResult));

        CompressionLevel level = policy(new StubLlm())
                .apply(window, metricsAt(0.66), new CompressionState(), 4);

        assertThat(level).isEqualTo(CompressionLevel.SNIP);
        // 保护区外的 TOOL_RESULT 与 AI_TEXT 都被头尾截断
        assertThat(oldResult.text()).contains("\n...\n").hasSize(4005);
        assertThat(oldAi.text()).contains("\n...\n").hasSize(4005);
        // 保护区内的块不动（oldUser 是锚点、newResult 在锚点之后）
        assertThat(oldUser.text()).isEqualTo("老问题".repeat(100));
        assertThat(newResult.text()).isEqualTo("z".repeat(5000));
    }

    @Test
    @DisplayName("SNIP：短于保留长度的候选块跳过；同档位重复执行不再命中")
    void snipSkipsShortAndAlreadyDisposed() {
        ContextWindow window = new ContextWindow();
        ContextBlock shortBlock = block("r1", BlockKind.TOOL_RESULT, "short");
        ContextBlock longBlock = block("r2", BlockKind.TOOL_RESULT, "x".repeat(5000));
        window.addAll(List.of(shortBlock, longBlock, block("u9", BlockKind.USER_INPUT, "近期内容")));

        CompressionPolicy policy = policy(new StubLlm());
        CompressionState state = new CompressionState();
        assertThat(policy.apply(window, metricsAt(0.66), state, 4)).isEqualTo(CompressionLevel.SNIP);
        assertThat(shortBlock.text()).isEqualTo("short");

        // 第二次 SNIP：候选块都已处置过，不再产生效果
        assertThat(policy.apply(window, metricsAt(0.66), state, 5)).isEqualTo(CompressionLevel.NONE);
    }

    // ═══════════════ PRUNE 档行为 ═══════════════

    @Test
    @DisplayName("PRUNE：AI_TEXT 不参与 PRUNE，仅 TOOL_RESULT 被清空或截断")
    void pruneSkipsAiText() {
        ContextWindow window = new ContextWindow();
        ContextBlock oldAi = block("a1", BlockKind.AI_TEXT, "x".repeat(5000));
        // PRUNE 时 AI_TEXT 不动；TOOL_RESULT 无副本 → 头尾截断
        ContextBlock oldResult = block("r1", BlockKind.TOOL_RESULT, "y".repeat(5000));
        window.addAll(List.of(oldAi, oldResult, block("u9", BlockKind.USER_INPUT, "近期内容")));

        CompressionLevel level = policy(new StubLlm())
                .apply(window, metricsAt(0.81), new CompressionState(), 4);

        assertThat(level).isEqualTo(CompressionLevel.PRUNE);
        // AI_TEXT 在 PRUNE 档不被处置
        assertThat(oldAi.text()).isEqualTo("x".repeat(5000));
        // TOOL_RESULT 头尾截断
        assertThat(oldResult.text()).contains("\n...\n");
    }

    @Test
    @DisplayName("PRUNE：recoverable 结果清空为带引用占位符，无副本结果退化为头尾截断")
    void pruneClearsRecoverableAndSnipsOthers() {
        ContextWindow window = new ContextWindow();
        ContextBlock withCopy = block("r1", BlockKind.TOOL_RESULT, "x".repeat(5000));
        withCopy.setRecoverable(true);
        withCopy.setRefId("art-9");
        ContextBlock noCopy = block("r2", BlockKind.TOOL_RESULT, "y".repeat(5000));
        window.addAll(List.of(withCopy, noCopy, block("u9", BlockKind.USER_INPUT, "近期内容")));

        policy(new StubLlm()).apply(window, metricsAt(0.81), new CompressionState(), 4);

        assertThat(withCopy.text())
                .contains("内容已清除")
                .contains("artifact://art-9")
                .contains("read_file");
        assertThat(noCopy.text()).contains("\n...\n");
    }

    @Test
    @DisplayName("PRUNE：recoverable 长参数骨架化为合法 JSON，路径来自入站盖章的 refId")
    void pruneSkeletonizesRecoverableLongArgs() throws Exception {
        ContextWindow window = new ContextWindow();
        ContextBlock longArgs = block("c1", BlockKind.TOOL_ARGS,
                JSON.writeValueAsString(java.util.Map.of(
                        "file_path", "/data/x.txt",
                        "content", "z".repeat(3000))));
        longArgs.setRecoverable(true);
        // 入站规则 ToolArgsRecoverRule 已把持久化位置盖到 refId
        longArgs.setRefId("/data/x.txt");
        ContextBlock shortArgs = block("c2", BlockKind.TOOL_ARGS,
                JSON.writeValueAsString(java.util.Map.of("path", "/data/y.txt")));
        shortArgs.setRecoverable(true);
        ContextBlock noCopyArgs = block("c3", BlockKind.TOOL_ARGS,
                JSON.writeValueAsString(java.util.Map.of("content", "w".repeat(3000))));
        window.addAll(List.of(longArgs, shortArgs, noCopyArgs, block("u9", BlockKind.USER_INPUT, "近期内容")));

        policy(new StubLlm()).apply(window, metricsAt(0.81), new CompressionState(), 4);

        JsonNode node = JSON.readTree(longArgs.text());
        assertThat(node.get("file_path").asText()).isEqualTo("/data/x.txt");
        // 长字段被清空，占位说明引用 refId 中的落盘路径
        assertThat(node.get("content").asText()).contains("已清空").contains("/data/x.txt");
        assertThat(shortArgs.text()).contains("/data/y.txt").doesNotContain("已清空");
        // 无副本参数不做骨架化
        assertThat(noCopyArgs.text()).doesNotContain("已清空");
    }

    @Test
    @DisplayName("PRUNE：recoverable 但入站未盖章位置时，骨架化降级为通用文案")
    void pruneSkeletonizeDegradesWithoutLocation() throws Exception {
        ContextWindow window = new ContextWindow();
        ContextBlock longArgs = block("c1", BlockKind.TOOL_ARGS,
                JSON.writeValueAsString(java.util.Map.of(
                        "target", "/data/x.txt",
                        "content", "z".repeat(3000))));
        longArgs.setRecoverable(true);
        // 不设 refId，模拟工具未提供 persistedLocation
        window.addAll(List.of(longArgs, block("u9", BlockKind.USER_INPUT, "近期内容")));

        policy(new StubLlm()).apply(window, metricsAt(0.81), new CompressionState(), 4);

        JsonNode node = JSON.readTree(longArgs.text());
        // target 字段短，保留原值；content 被清空但不含具体路径
        assertThat(node.get("target").asText()).isEqualTo("/data/x.txt");
        assertThat(node.get("content").asText())
                .contains("已清空")
                .contains("内容已落盘")
                .doesNotContain("/data/x.txt");
    }

    // ═══════════════ SUMMARIZE 档行为 ═══════════════

    @Test
    @DisplayName("SUMMARIZE：摘要写入状态后才删原文，只保留保护区")
    void summarizeBeforeRemove() {
        ContextWindow window = new ContextWindow();
        ContextBlock oldUser = block("u1", BlockKind.USER_INPUT, "老问题");
        ContextBlock oldAi = block("a1", BlockKind.AI_TEXT, "老回答");
        ContextBlock oldCall = block("c1", BlockKind.TOOL_ARGS, "{\"a\":1}");
        ContextBlock oldResult = block("r1", BlockKind.TOOL_RESULT, "工具输出");
        ContextBlock newUser = block("u9", BlockKind.USER_INPUT, "新问题");
        window.addAll(List.of(oldUser, oldAi, oldCall, oldResult, newUser));

        StubLlm llm = new StubLlm();
        CompressionState state = new CompressionState();

        CompressionLevel level = policy(llm)
                .apply(window, metricsAt(0.93), state, 11);

        assertThat(level).isEqualTo(CompressionLevel.SUMMARIZE);
        assertThat(state.getLastSummary()).contains("<summary>").contains("这是测试摘要。");
        // 保护区外的块全部删除，用户消息也不例外；只剩保护区内的 newUser
        assertThat(window.blocks()).containsExactly(newUser);
        // 摘要 prompt 喂进了全部被裁剪内容，用户原话是诉求的唯一来源
        String prompt = llm.captured.get(0).get(1).toString();
        assertThat(prompt).contains("[用户] 老问题").contains("老回答").contains("工具输出");
    }

    @Test
    @DisplayName("SUMMARIZE：用户消息进入摘要区间——诉求由摘要承载，原文释放")
    void summarizeConsumesUserInputToo() {
        ContextWindow window = new ContextWindow();
        // 第一条用户消息远在保护区外，与它同轮的助手块一起被裁剪
        window.addAll(List.of(
                block("u1", BlockKind.USER_INPUT, "帮我重构这个项目"),
                block("a1", BlockKind.AI_TEXT, "开始重构"),
                block("u9", BlockKind.USER_INPUT, "近期内容")));

        StubLlm llm = new StubLlm();
        CompressionState state = new CompressionState();

        CompressionLevel level = policy(llm).apply(window, metricsAt(0.93), state, 11);

        assertThat(level).isEqualTo(CompressionLevel.SUMMARIZE);
        // 用户消息不再逐字保留，其诉求改由摘要承载
        assertThat(window.blocks()).hasSize(1);
        assertThat(window.blocks().get(0).kind()).isEqualTo(BlockKind.USER_INPUT);

        String prompt = llm.captured.get(0).get(1).toString();
        assertThat(prompt).contains("帮我重构这个项目");
        // prompt 必须声明原文会被永久删除，摘要是诉求的唯一依据
        assertThat(prompt).contains("永久删除").contains("唯一依据");
    }

    @Test
    @DisplayName("SUMMARIZE：增量合并时旧摘要进入 prompt")
    void summarizeMergesExistingSummary() {
        ContextWindow window = new ContextWindow();
        window.addAll(List.of(
                block("a1", BlockKind.AI_TEXT, "第一段回答"),
                block("u9", BlockKind.USER_INPUT, "近期内容")));

        StubLlm llm = new StubLlm();
        CompressionState state = new CompressionState();
        state.setLastSummary("<summary>\n旧的摘要内容\n</summary>");

        policy(llm).apply(window, metricsAt(0.93), state, 10);

        String prompt = llm.captured.get(0).get(1).toString();
        assertThat(prompt).contains("旧的摘要内容");
    }

    @Test
    @DisplayName("SUMMARIZE：LLM 失败时降级为指引文案并照常删除")
    void summarizeFallsBackOnLlmFailure() {
        ContextWindow window = new ContextWindow();
        ContextBlock oldAi = block("a1", BlockKind.AI_TEXT, "老回答");
        ContextBlock tail = block("u9", BlockKind.USER_INPUT, "近期内容");
        window.addAll(List.of(oldAi, tail));

        StubLlm llm = new StubLlm();
        llm.fail = true;
        CompressionState state = new CompressionState();

        CompressionLevel level = policy(llm)
                .apply(window, metricsAt(0.93), state, 11);

        assertThat(level).isEqualTo(CompressionLevel.SUMMARIZE);
        assertThat(state.getLastSummary()).contains("摘要生成失败").contains("/tmp/transcript.jsonl");
        assertThat(window.blocks()).containsExactly(tail);
    }

    @Test
    @DisplayName("SUMMARIZE：保护区外无可摘要内容时不动作")
    void summarizeNoopWhenNothingRemovable() {
        ContextWindow window = new ContextWindow();
        window.addAll(List.of(block("u9", BlockKind.USER_INPUT, "只有新内容")));

        CompressionState state = new CompressionState();
        CompressionLevel level = policy(new StubLlm())
                .apply(window, metricsAt(0.93), state, 11);

        assertThat(level).isEqualTo(CompressionLevel.NONE);
        assertThat(state.getLastSummary()).isNull();
    }

    @Test
    @DisplayName("pinned 块豁免一切处置：即使水位极高也不被压缩或删除")
    void pinnedBlockExemptFromAllDisposal() {
        ContextWindow window = new ContextWindow();
        // 只有一条用户输入，它就是锚点也是 pinned 块，整窗口都在保护区内
        ContextBlock onlyUser = block("u", BlockKind.USER_INPUT, "x".repeat(5000));
        window.addAll(List.of(onlyUser));

        CompressionState state = new CompressionState();
        CompressionLevel level = policy(new StubLlm())
                .apply(window, metricsAt(0.95), state, 11);

        // 唯一可处置块是 pinned，跳过；无任何块被处置 → NONE
        assertThat(level).isEqualTo(CompressionLevel.NONE);
        assertThat(onlyUser.text()).isEqualTo("x".repeat(5000));
        assertThat(onlyUser.isPinned()).isTrue();
    }
}
