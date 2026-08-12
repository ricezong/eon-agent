package cn.kong.eon.agent.context;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文构建器。
 *
 * <h3>分层组装</h3>
 * <ul>
 *   <li>基础层（所有请求必走）：System Prompt + 滚动摘要 + 历史消息 + 尾部保护区</li>
 *   <li>Agent 层（按需注入）：ToolCatalog + Navigator</li>
 * </ul>
 *
 * <h3>物理拼装顺序</h3>
 * <pre>
 *   messages[0]          System Prompt（basePrompt，完全冻结，吃 KV Cache）
 *   messages[1]?         Summary（压缩后才有）
 *   messages[2..N]       Transcript（历史消息，可被 Snip/Prune 压缩）
 *   messages[N+1]?       ToolCatalog（ASSISTED/TASK_MULTI 才注入，独立消息）
 *   messages[N+2]?       Navigator（TodoNavigator 激活后才有）
 *   messages[N+3..End]   TailGuard（尾部保护区，最近 3 轮，绝不裁剪）
 * </pre>
 *
 * <h3>KV Cache 友好</h3>
 * System Prompt 不拼接任何动态内容，tool_catalog 和 navigator 作为独立消息注入，
 * 放在 transcript 之后，不破坏 System Prompt 的前缀稳定性。
 *
 * <h3>使用方式</h3>
 * CapabilityModule 通过 beforeModelCall 向 ContextBuilder 注入内容，
 * EonAgent 在 beforeModelCall 之后调用 build() 生成最终 messages。
 */
public class ContextBuilder {

    private String systemPrompt;
    private String summary;
    private String navigator;
    private String toolCatalog;
    private String runtimeNudges;
    private List<ChatMessage> transcript;
    private List<ChatMessage> tailGuard;

    public ContextBuilder setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        return this;
    }

    public ContextBuilder setSummary(String summary) {
        this.summary = summary;
        return this;
    }

    public ContextBuilder setNavigator(String navigator) {
        this.navigator = navigator;
        return this;
    }

    public ContextBuilder setToolCatalog(String toolCatalog) {
        this.toolCatalog = toolCatalog;
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

    public ContextBuilder setTailGuard(List<ChatMessage> tailGuard) {
        this.tailGuard = tailGuard;
        return this;
    }

    /**
     * 构建最终的 messages 列表。
     *
     * 顺序：System Prompt → Summary → Transcript → ToolCatalog → Navigator → TailGuard
     */
    public List<ChatMessage> build() {
        List<ChatMessage> result = new ArrayList<>();

        // ① 基础层：System Prompt（冻结，吃 KV Cache）
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            result.add(SystemMessage.from(systemPrompt));
        }

        // ② 基础层：滚动摘要（压缩后才有）
        if (summary != null && !summary.isBlank()) {
            result.add(SystemMessage.from("## [Summary] 历史对话摘要\n" + summary));
        }

        // ③ 基础层：历史消息（可被 Snip/Prune 压缩）
        if (transcript != null) {
            result.addAll(transcript);
        }

        // ④ Agent 层：工具目录（ASSISTED/TASK_MULTI 才注入，独立消息不破坏 System Prompt 缓存）
        if (toolCatalog != null && !toolCatalog.isBlank()) {
            result.add(UserMessage.from("tool_catalog", toolCatalog));
        }

        // ⑤ Agent 层：Navigator（TodoNavigator 激活后才有）
        if (navigator != null && !navigator.isBlank()) {
            result.add(UserMessage.from("navigator", navigator));
        }

        // ⑤b Agent 层：运行时提醒（NudgeRenderer 始终注入，不依赖 TodoNavigator）
        if (runtimeNudges != null && !runtimeNudges.isBlank()) {
            result.add(UserMessage.from("runtime_nudges", runtimeNudges));
        }

        // ⑥ 基础层：尾部保护区（最近 3 轮，绝不裁剪）
        if (tailGuard != null) {
            result.addAll(tailGuard);
        }

        return result;
    }

    /**
     * 估算当前上下文的 token 数（粗略：字符数/4）。
     */
    public long estimateTokens() {
        long chars = 0;
        if (systemPrompt != null) chars += systemPrompt.length();
        if (summary != null) chars += summary.length();
        if (toolCatalog != null) chars += toolCatalog.length();
        if (navigator != null) chars += navigator.length();
        if (transcript != null) {
            for (ChatMessage msg : transcript) {
                chars += extractText(msg).length();
            }
        }
        if (tailGuard != null) {
            for (ChatMessage msg : tailGuard) {
                chars += extractText(msg).length();
            }
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
