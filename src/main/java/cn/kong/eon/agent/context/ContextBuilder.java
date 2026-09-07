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
 * 上下文构建器。分层组装发送给 LLM 的 messages：
 * System → Memories → Summary → Transcript → Todo → Nudges。
 */
public class ContextBuilder {

    private String systemPrompt;
    private String memories;
    private String summary;
    private String todo;
    private String nudges;
    private ContextWindow window;
    private TokenCountEstimator tokenCountEstimator;

    // 度量口径
    private long toolSchemaTokens;
    private long outputReserveTokens;
    private long contextMaxTokens;

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

    /** 设置 transcript 数据源。 */
    public void setWindow(ContextWindow window) {
        this.window = window;
    }

    public ContextWindow getWindow() {
        return window;
    }

    /** transcript 的消息视图。 */
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
        if (memories != null && !memories.isBlank()) {
            result.add(UserMessage.from("memories", wrap("memories", memories)));
        }
        if (summary != null && !summary.isBlank()) {
            result.add(UserMessage.from("summary", wrap("summary", summary)));
        }
        List<ChatMessage> transcript = getTranscript();
        if (!transcript.isEmpty()) {
            result.addAll(transcript);
        }
        if (todo != null && !todo.isBlank()) {
            result.add(UserMessage.from("todo", wrap("todo", todo)));
        }
        if (nudges != null && !nudges.isBlank()) {
            result.add(UserMessage.from("nudges", wrap("nudges", nudges)));
        }

        return result;
    }

    /** 用 XML 标签包裹内容。 */
    private static String wrap(String label, String content) {
        return "<" + label + ">\n" + content + "\n</" + label + ">";
    }

    /** 完整度量。 */
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
}
