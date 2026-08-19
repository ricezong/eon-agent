package cn.kong.eon.agent.context;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文构建器。分层组装最终发送给 LLM 的 messages。
 *
 * 物理顺序：
 *   System Prompt → Summary → Transcript → ToolCatalog
 *   → Navigator → RuntimeNudges → TailGuard
 *
 * System Prompt 不拼接动态内容，保证 KV Cache 前缀稳定。
 * Hook 通过 beforeModelCall 注入内容，EonAgent 调用 build() 生成最终 messages。
 */
public class ContextBuilder {

    private String systemPrompt;
    private String summary;
    private String navigator;
    private String toolCatalog;
    private String runtimeNudges;
    private List<ChatMessage> transcript;
    private List<ChatMessage> tailGuard;

    public ContextBuilder setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
    public ContextBuilder setSummary(String summary) { this.summary = summary; return this; }
    public ContextBuilder setNavigator(String navigator) { this.navigator = navigator; return this; }
    public ContextBuilder setToolCatalog(String toolCatalog) { this.toolCatalog = toolCatalog; return this; }
    public ContextBuilder setRuntimeNudges(String runtimeNudges) { this.runtimeNudges = runtimeNudges; return this; }
    public ContextBuilder setTranscript(List<ChatMessage> transcript) { this.transcript = transcript; return this; }
    public List<ChatMessage> getTranscript() { return transcript; }
    public ContextBuilder setTailGuard(List<ChatMessage> tailGuard) { this.tailGuard = tailGuard; return this; }

    public List<ChatMessage> build() {
        List<ChatMessage> result = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            result.add(SystemMessage.from(systemPrompt));
        }
        if (summary != null && !summary.isBlank()) {
            result.add(SystemMessage.from("## [Summary] 历史对话摘要\n" + summary));
        }
        if (transcript != null) {
            result.addAll(transcript);
        }
        if (toolCatalog != null && !toolCatalog.isBlank()) {
            result.add(UserMessage.from("tool_catalog", toolCatalog));
        }
        if (navigator != null && !navigator.isBlank()) {
            result.add(UserMessage.from("navigator", navigator));
        }
        if (runtimeNudges != null && !runtimeNudges.isBlank()) {
            result.add(UserMessage.from("runtime_nudges", runtimeNudges));
        }
        if (tailGuard != null) {
            result.addAll(tailGuard);
        }

        return result;
    }

    /** 粗略估算 token 数（字符数/4）。 */
    public long estimateTokens() {
        long chars = 0;
        if (systemPrompt != null) chars += systemPrompt.length();
        if (summary != null) chars += summary.length();
        if (toolCatalog != null) chars += toolCatalog.length();
        if (navigator != null) chars += navigator.length();
        if (transcript != null) {
            for (ChatMessage msg : transcript) chars += extractText(msg).length();
        }
        if (tailGuard != null) {
            for (ChatMessage msg : tailGuard) chars += extractText(msg).length();
        }
        return chars / 4;
    }

    private String extractText(ChatMessage msg) {
        if (msg instanceof SystemMessage sm) return sm.text();
        if (msg instanceof UserMessage um) return um.singleText();
        if (msg instanceof dev.langchain4j.data.message.AiMessage am) return am.text() != null ? am.text() : "";
        if (msg instanceof dev.langchain4j.data.message.ToolExecutionResultMessage trm) return trm.text() != null ? trm.text() : "";
        return "";
    }
}
