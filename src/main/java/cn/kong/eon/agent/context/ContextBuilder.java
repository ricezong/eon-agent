package cn.kong.eon.agent.context;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文构建器。分层组装发送给 LLM 的 messages。
 * 物理顺序：
 * System Prompt → Summary → Transcript → Memories → Navigator → RuntimeNudges。
 * System Prompt 不拼接动态内容，保证 KV Cache 前缀稳定。
 * 工具通过 API 请求的 tools 字段注入，不在 messages 中重复。
 */
public class ContextBuilder {

    private String systemPrompt;
    private String summary;         // 历史对话摘要
    private String memories;        // 跨会话记忆
    private String navigator;       // Todo 列表导航
    private String runtimeNudges;   // 运行时提醒（本轮有效）
    private List<ChatMessage> transcript;
    private TokenCountEstimator tokenCountEstimator; // 精确 token 估算器

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

    public ContextBuilder setTranscript(List<ChatMessage> transcript) {
        this.transcript = transcript;
        return this;
    }

    public List<ChatMessage> getTranscript() {
        return transcript;
    }

    public ContextBuilder setTokenCountEstimator(TokenCountEstimator estimator) {
        this.tokenCountEstimator = estimator;
        return this;
    }

    public List<ChatMessage> build() {
        List<ChatMessage> result = new ArrayList<>();

        // 1. System Prompt（KV Cache 前缀）
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            result.add(SystemMessage.from(systemPrompt));
        }
        // 2. Summary
        if (summary != null && !summary.isBlank()) {
            result.add(SystemMessage.from("<summary>\n" + summary + "\n</summary>"));
        }
        // 3. Transcript
        if (transcript != null) {
            result.addAll(transcript);
        }
        // 4. Memories（排在 Transcript 之后，避免被压缩算法截断）
        if (memories != null && !memories.isBlank()) {
            result.add(UserMessage.from("memories", memories));
        }
        // 5. Navigator
        if (navigator != null && !navigator.isBlank()) {
            result.add(UserMessage.from("navigator", navigator));
        }
        // 6. Runtime Nudges
        if (runtimeNudges != null && !runtimeNudges.isBlank()) {
            result.add(UserMessage.from("runtime_nudges", runtimeNudges));
        }

        return result;
    }

    /**
     * 使用 LangChain4j TokenCountEstimator 精确估算 token 数。
     * 若未注入 estimator，回退到 chars/2 的粗略估算。
     */
    public long estimateTokens() {
        if (tokenCountEstimator != null) {
            long tokens = 0;
            if (systemPrompt != null) tokens += tokenCountEstimator.estimateTokenCountInText(systemPrompt);
            if (summary != null) tokens += tokenCountEstimator.estimateTokenCountInText(summary);
            if (memories != null) tokens += tokenCountEstimator.estimateTokenCountInText(memories);
            if (navigator != null) tokens += tokenCountEstimator.estimateTokenCountInText(navigator);
            if (transcript != null) tokens += tokenCountEstimator.estimateTokenCountInMessages(transcript);
            return tokens;
        }
        // 回退：chars/2 粗略估算
        long chars = 0;
        if (systemPrompt != null) chars += systemPrompt.length();
        if (summary != null) chars += summary.length();
        if (memories != null) chars += memories.length();
        if (navigator != null) chars += navigator.length();
        if (transcript != null) {
            for (ChatMessage msg : transcript) chars += extractText(msg).length();
        }
        return chars / 2;
    }

    private String extractText(ChatMessage msg) {
        if (msg instanceof SystemMessage sm) return sm.text();
        if (msg instanceof UserMessage um) return um.singleText();
        if (msg instanceof dev.langchain4j.data.message.AiMessage am) return am.text() != null ? am.text() : "";
        if (msg instanceof dev.langchain4j.data.message.ToolExecutionResultMessage trm)
            return trm.text() != null ? trm.text() : "";
        return "";
    }
}
