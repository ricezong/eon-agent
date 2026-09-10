package cn.kong.eon.agent.flush;

import cn.kong.eon.session.SessionState;
import cn.kong.eon.agent.exec.ToolExecResult;
import cn.kong.eon.store.JsonlStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 消息回填器。将 AI 消息和工具结果回填到上下文，清理会话临时状态。
 * 所有回填都经过 {@link JsonlStore#append} → 入站管线，不存在绕过关卡的路径。
 * 工具结果以原始输出回填，落盘与格式化交给入站规则完成。
 */
public class MessageFlusher {
    private final JsonlStore jsonlStore;

    public MessageFlusher(JsonlStore jsonlStore) {
        this.jsonlStore = jsonlStore;
    }

    /**
     * 回填 AI 消息和工具结果，清理临时状态。
     * 成功调用 id 集合随消息一起进账本，回放时据此还原工具结果的执行状态。
     */
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

        // 仅当有文本或工具调用时才回填 AiMessage
        if (hasText || hasCalls) {
            AiMessage aiMsg = hasText
                    ? AiMessage.from(assistantText, pendingCalls)
                    : AiMessage.from(pendingCalls);
            jsonlStore.append(aiMsg, succeeded);
        }

        if (toolResults != null) {
            for (ToolExecResult result : toolResults) {
                ToolExecutionResultMessage toolResultMsg = ToolExecutionResultMessage.from(result.toolCallId(), result.toolName(), result.content());
                jsonlStore.append(toolResultMsg, succeeded);
            }
        }

        state.setPendingToolCalls(null);
        state.setLastToolResults(null);
        state.setLastAssistantText(null);
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
