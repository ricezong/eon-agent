package cn.kong.eon.agent.context;

import cn.kong.eon.agent.context.block.BlockKind;
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
 * 物理顺序：System Prompt → Summary → Transcript → Memories → TODO → Nudges。
 * Transcript 部分以 {@link ContextWindow}（内容块序列）为数据源，只在 {@link #build()} 时投射回消息类型。
 */
public class ContextBuilder {

    private String systemPrompt;
    private String memories;
    private String summary;
    private String todo;
    private String nudges;
    private ContextWindow window;
    private TokenCountEstimator tokenCountEstimator;

    // 度量口径：工具 schema 与输出预留是每轮真实发送但过去完全不计入的量
    private long toolSchemaTokens;
    private long outputReserveTokens;
    private long contextMaxTokens;
    private long budgetUsedTokens;
    private long budgetMaxTokens;

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public void setMemories(String memories) {
        this.memories = memories;
    }

    public void setTodo(String todo) {
        this.todo = todo;
    }

    public void setNudges(String nudges) {
        this.nudges = nudges;
    }

    /** 设置 transcript 数据源。压缩策略对窗口的就地修改会自动反映到这里。 */
    public ContextBuilder setWindow(ContextWindow window) {
        this.window = window;
        return this;
    }

    public ContextWindow getWindow() {
        return window;
    }

    /** transcript 的消息视图（由块组装而来）。 */
    public List<ChatMessage> getTranscript() {
        return window != null ? window.toMessages() : List.of();
    }

    public void setTokenCountEstimator(TokenCountEstimator estimator) {
        this.tokenCountEstimator = estimator;
    }

    public void setToolSchemaTokens(long tokens) {
        this.toolSchemaTokens = tokens;
    }

    public void setOutputReserveTokens(long tokens) {
        this.outputReserveTokens = tokens;
    }

    public void setContextMaxTokens(long tokens) {
        this.contextMaxTokens = tokens;
    }

    public void setBudgetTokens(long used, long max) {
        this.budgetUsedTokens = used;
        this.budgetMaxTokens = max;
    }

    /** 组装最终发送给 LLM 的消息列表。 */
    public List<ChatMessage> build() {
        List<ChatMessage> result = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            result.add(SystemMessage.from(systemPrompt));
        }
        if (summary != null && !summary.isBlank()) {
            result.add(SystemMessage.from(summary));
        }
        List<ChatMessage> transcript = getTranscript();
        if (!transcript.isEmpty()) {
            result.addAll(transcript);
        }
        // Memories 排在 Transcript 之后，避免被压缩算法截断
        if (memories != null && !memories.isBlank()) {
            result.add(UserMessage.from("memories", memories));
        }
        if (todo != null && !todo.isBlank()) {
            result.add(UserMessage.from("todo", todo));
        }
        if (nudges != null && !nudges.isBlank()) {
            result.add(UserMessage.from("nudges", nudges));
        }

        return result;
    }

    /** 本轮真实发送 token 数 = transcript + 锚点层 + 工具 schema + 输出预留。 */
    public long estimateTokens() {
        return transcriptTokens() + anchorTokens() + toolSchemaTokens + outputReserveTokens;
    }

    /** 完整度量：水位、构成分解、预算投影。 */
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

    /** 按块类型统计 token。 */
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
        if (todo != null) tokens += estimate(todo);
        if (nudges != null) tokens += estimate(nudges);
        return tokens;
    }

    private long estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        if (tokenCountEstimator != null) {
            return tokenCountEstimator.estimateTokenCountInText(text);
        }
        return text.length() / 2;
    }

    /** 便捷入口：从会话状态填充预算口径后取度量。 */
    public ContextMetrics metrics(SessionState state) {
        if (state != null) {
            this.budgetUsedTokens = state.getUsageAccum().getTotalTokens();
        }
        return metrics();
    }
}
