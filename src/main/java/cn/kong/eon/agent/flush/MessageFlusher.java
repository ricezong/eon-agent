package cn.kong.eon.agent.flush;

import cn.kong.eon.agent.exec.ToolExecResult;
import cn.kong.eon.session.SessionState;
import cn.kong.eon.store.JsonlStore;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 消息回填器。将 AI 消息和工具结果回填到上下文，清理临时状态。
 * 工具结果以原始输出回填，落盘与格式化交给入站管线。
 */
public class MessageFlusher {
    private final JsonlStore jsonlStore;

    public MessageFlusher(JsonlStore jsonlStore) {
        this.jsonlStore = jsonlStore;
    }

    /** 回填 AI 消息和工具结果，清理临时状态。 */
    public void flush(SessionState state) {
        String assistantText = state.getLastAssistantText();
        var pendingCalls = state.getPendingToolCalls();
        boolean hasText = assistantText != null && !assistantText.isBlank();
        boolean hasCalls = pendingCalls != null && !pendingCalls.isEmpty();

        // 异常路径下可能既无文本又无工具调用，直接清理并返回
        if (!hasText && !hasCalls) {
            state.setPendingToolCalls(null);
            state.setLastToolResults(null);
            state.setLastAssistantText(null);
            return;
        }

        List<ToolExecResult> toolResults = state.getLastToolResults();
        Set<String> succeeded = succeededIds(toolResults);
        String thinking = state.getLastThinking();
        AiMessage aiMsg = buildAiMessage(assistantText, pendingCalls, hasText, thinking);
        jsonlStore.append(aiMsg, succeeded);


        if (toolResults != null) {
            for (ToolExecResult result : toolResults) {
                ToolExecutionResultMessage toolResultMsg = ToolExecutionResultMessage.from(result.toolCallId(), result.toolName(), result.content());
                jsonlStore.append(toolResultMsg, succeeded, result.structuredContent());
            }
        }

        state.setPendingToolCalls(null);
        state.setLastToolResults(null);
        state.setLastAssistantText(null);
        state.setLastThinking(null);
    }

    /** 构建 AiMessage，携带 thinking 用于账本持久化。 */
    private static AiMessage buildAiMessage(String text, List<ToolExecutionRequest> calls,
                                             boolean hasText, String thinking) {
        var builder = AiMessage.builder();
        if (hasText) {
            builder.text(text);
        }
        if (calls != null && !calls.isEmpty()) {
            builder.toolExecutionRequests(calls);
        }
        if (thinking != null && !thinking.isBlank()) {
            builder.thinking(thinking);
        }
        return builder.build();
    }

    /** 从工具执行结果中提取成功的调用 id 集合。 */
    private static Set<String> succeededIds(List<ToolExecResult> results) {
        Set<String> ids = new HashSet<>();
        if (results == null) return ids;
        for (ToolExecResult r : results) {
            if (r.success() && r.toolCallId() != null) {
                ids.add(r.toolCallId());
            }
        }
        return ids;
    }
}
