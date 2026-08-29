package cn.kong.eon.agent.context;

import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.BlockProjector;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.model.SessionState;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文构建器。分层组装发送给 LLM 的 messages。
 * 物理顺序：System Prompt → Summary → Transcript → Memories → Navigator → RuntimeNudges。
 * System Prompt 不拼接动态内容，保证 KV Cache 前缀稳定。
 * <p>
 * Transcript 部分以 {@link ContextWindow}（内容块序列）为数据源，
 * 只在 {@link #build()} 时投射回 LangChain4j 消息类型。
 */
public class ContextBuilder {

    private String systemPrompt;
    private String summary;
    private String memories;
    private String navigator;
    private String runtimeNudges;
    private ContextWindow window;
    private TokenCountEstimator tokenCountEstimator;

    // ── 度量口径（原 estimateTokens 完全漏算的两项） ──
    private long toolSchemaTokens;
    private long outputReserveTokens;
    private long contextMaxTokens;
    private long budgetUsedTokens;
    private long budgetMaxTokens;

    public ContextBuilder setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        return this;
    }

    public ContextBuilder setSummary(String summary) {
        this.summary = summary;
        return this;
    }

    public ContextBuilder setMemories(String memories) {
        this.memories = memories;
        return this;
    }

    public ContextBuilder setNavigator(String navigator) {
        this.navigator = navigator;
        return this;
    }

    public ContextBuilder setRuntimeNudges(String runtimeNudges) {
        this.runtimeNudges = runtimeNudges;
        return this;
    }

    /**
     * 设置 transcript 数据源。压缩策略对窗口的就地修改会自动反映到这里。
     */
    public ContextBuilder setWindow(ContextWindow window) {
        this.window = window;
        return this;
    }

    public ContextWindow getWindow() {
        return window;
    }

    /**
     * 兼容入口：把消息列表整体投射为窗口（丢弃既有的块级状态）。
     */
    public ContextBuilder setTranscript(List<ChatMessage> transcript) {
        if (transcript == null) {
            this.window = new ContextWindow();
            return this;
        }
        this.window = new ContextWindow();
        this.window.addAll(BlockProjector.explodeAll(transcript, 0, null));
        return this;
    }

    /**
     * transcript 的消息视图（由块组装而来）。
     */
    public List<ChatMessage> getTranscript() {
        return window != null ? window.toMessages() : List.of();
    }

    public ContextBuilder setTokenCountEstimator(TokenCountEstimator estimator) {
        this.tokenCountEstimator = estimator;
        return this;
    }

    public ContextBuilder setToolSchemaTokens(long tokens) {
        this.toolSchemaTokens = tokens;
        return this;
    }

    public ContextBuilder setOutputReserveTokens(long tokens) {
        this.outputReserveTokens = tokens;
        return this;
    }

    public ContextBuilder setContextMaxTokens(long tokens) {
        this.contextMaxTokens = tokens;
        return this;
    }

    public ContextBuilder setBudgetTokens(long used, long max) {
        this.budgetUsedTokens = used;
        this.budgetMaxTokens = max;
        return this;
    }

    public List<ChatMessage> build() {
        List<ChatMessage> result = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            result.add(SystemMessage.from(systemPrompt));
        }
        if (summary != null && !summary.isBlank()) {
            result.add(SystemMessage.from("<summary>\n" + summary + "\n</summary>"));
        }
        List<ChatMessage> transcript = getTranscript();
        if (!transcript.isEmpty()) {
            result.addAll(transcript);
        }
        // Memories 排在 Transcript 之后，避免被压缩算法截断
        if (memories != null && !memories.isBlank()) {
            result.add(UserMessage.from("memories", memories));
        }
        if (navigator != null && !navigator.isBlank()) {
            result.add(UserMessage.from("navigator", navigator));
        }
        if (runtimeNudges != null && !runtimeNudges.isBlank()) {
            result.add(UserMessage.from("runtime_nudges", runtimeNudges));
        }

        return result;
    }

    /**
     * 本轮真实发送 token 数 = transcript + 锚点层 + 工具 schema + 输出预留。
     * <p>
     * 后两项过去完全漏算：工具 schema 每轮随请求发送（9 内置 + MCP 约数千 token），
     * 输出预留是向模型承诺的响应空间，两者都真实占用窗口与预算。
     */
    public long estimateTokens() {
        return transcriptTokens() + anchorTokens() + toolSchemaTokens + outputReserveTokens;
    }

    /**
     * 完整度量：水位、构成分解、预算投影。
     */
    public ContextMetrics metrics() {
        Map<BlockKind, Long> byKind = tokensByKind();
        long transcript = 0;
        for (long v : byKind.values()) transcript += v;

        return new ContextMetrics(
                transcript,
                anchorTokens(),
                toolSchemaTokens,
                outputReserveTokens,
                contextMaxTokens,
                budgetUsedTokens,
                budgetMaxTokens,
                byKind);
    }

    /**
     * 按块类型统计 token。这是"上下文被谁占满"的直接答案——
     * 过去要写脚本翻 transcript 才能得到。
     */
    public Map<BlockKind, Long> tokensByKind() {
        Map<BlockKind, Long> byKind = new EnumMap<>(BlockKind.class);
        if (window == null) return byKind;
        for (ContextBlock block : window.blocks()) {
            long tokens = estimate(block.text());
            byKind.merge(block.kind(), tokens, Long::sum);
        }
        return byKind;
    }

    private long transcriptTokens() {
        long total = 0;
        for (long v : tokensByKind().values()) total += v;
        return total;
    }

    private long anchorTokens() {
        long tokens = 0;
        if (systemPrompt != null) tokens += estimate(systemPrompt);
        if (summary != null) tokens += estimate(summary);
        if (memories != null) tokens += estimate(memories);
        if (navigator != null) tokens += estimate(navigator);
        if (runtimeNudges != null) tokens += estimate(runtimeNudges);
        return tokens;
    }

    private long estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        if (tokenCountEstimator != null) {
            return tokenCountEstimator.estimateTokenCountInText(text);
        }
        return text.length() / 2;
    }

    /**
     * 便捷入口：从会话状态填充预算口径后取度量。
     */
    public ContextMetrics metrics(SessionState state) {
        if (state != null) {
            this.budgetUsedTokens = state.getUsageAccum().getTotalTokens();
        }
        return metrics();
    }
}
