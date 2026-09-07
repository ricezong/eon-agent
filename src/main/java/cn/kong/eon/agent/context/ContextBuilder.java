package cn.kong.eon.agent.context;

import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;
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

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    /** 设置摘要正文。标签由本类在组装时统一添加，这里只收内容。 */
    public void setSummary(String summary) {
        this.summary = summary;
    }

    /** 设置记忆内容。标签由本类在组装时统一添加，这里只收内容。 */
    public void setMemories(String memories) {
        this.memories = memories;
    }

    /** 设置任务列表内容。标签由本类在组装时统一添加，这里只收内容。 */
    public void setTodo(String todo) {
        this.todo = todo;
    }

    /** 设置提醒内容。标签由本类在组装时统一添加，这里只收内容。 */
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

    /** 组装最终发送给 LLM 的消息列表。 */
    public List<ChatMessage> build() {
        List<ChatMessage> result = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            result.add(SystemMessage.from(systemPrompt));
        }
        String summarySection = section(ContextTags.SUMMARY, summary);
        if (summarySection != null) {
            result.add(SystemMessage.from(summarySection));
        }
        List<ChatMessage> transcript = getTranscript();
        if (!transcript.isEmpty()) {
            result.addAll(transcript);
        }
        // Memories 排在 Transcript 之后，避免被压缩算法截断
        String memoriesSection = section(ContextTags.MEMORIES, memories);
        if (memoriesSection != null) {
            result.add(UserMessage.from(ContextTags.MEMORIES, memoriesSection));
        }
        String todoSection = section(ContextTags.TODO, todo);
        if (todoSection != null) {
            result.add(UserMessage.from(ContextTags.TODO, todoSection));
        }
        String nudgesSection = section(ContextTags.NUDGES, nudges);
        if (nudgesSection != null) {
            result.add(UserMessage.from(ContextTags.NUDGES, nudgesSection));
        }

        return result;
    }

    /** 注入段：空内容返回 null（不加空标签），非空则套上段级标签。 */
    private static String section(String tag, String content) {
        return (content == null || content.isBlank()) ? null : ContextTags.wrap(tag, content);
    }

    /** 本轮真实发送 token 数 = transcript + 锚点层 + 工具 schema + 输出预留。 */
    public long estimateTokens() {
        return transcriptTokens() + anchorTokens() + toolSchemaTokens + outputReserveTokens;
    }

    /** 完整度量：水位、构成分解。 */
    public ContextMetrics metrics() {
        Map<BlockKind, Long> byKind = tokensByKind();
        long transcript = 0;
        for (long v : byKind.values()) {
            transcript += v;
        }

        return new ContextMetrics(
                transcript,
                anchorTokens(),
                toolSchemaTokens,
                outputReserveTokens,
                contextMaxTokens,
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
        tokens += estimate(section(ContextTags.SUMMARY, summary));
        tokens += estimate(section(ContextTags.MEMORIES, memories));
        tokens += estimate(section(ContextTags.TODO, todo));
        tokens += estimate(section(ContextTags.NUDGES, nudges));
        return tokens;
    }

    private long estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        if (tokenCountEstimator != null) {
            return tokenCountEstimator.estimateTokenCountInText(text);
        }
        return text.length() / 2;
    }
}
