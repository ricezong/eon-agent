package cn.kong.eon.agent.support;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolExecutionResult;
import cn.kong.eon.store.JsonlStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 消息回填器。将 AI 消息和工具结果回填到上下文，清理会话临时状态，
 * 在 stop 流程中检查并回填 pending 消息。
 * <p>
 * 所有回填都经过 {@link JsonlStore#append} → 入站管线，不存在绕过关卡的路径。
 * 工具结果在此处还是<b>原始输出</b>——落盘、格式化都交给入站规则完成，
 * 因此"工具结果有策略、工具参数裸奔"的不对称不再存在：
 * 两者都是入站块，走同一条管线，区别只在 Retention 标签。
 */
public class MessageFinalizer {
    private final JsonlStore jsonlStore;

    public MessageFinalizer(JsonlStore jsonlStore) {
        this.jsonlStore = jsonlStore;
    }

    /**
     * 回填 AI 消息和工具结果，清理临时状态。
     */
    public void finalizeAndAppend(SessionState state) {
        String assistantText = state.getLastAssistantText();
        var pendingCalls = state.getPendingToolCalls();
        boolean hasText = assistantText != null && !assistantText.isBlank();
        boolean hasCalls = pendingCalls != null && !pendingCalls.isEmpty();

        List<ToolExecutionResult> toolResults = state.getLastToolResults();
        // 卸载的安全边界：只有执行成功的调用才保证参数已真正落盘
        Set<String> succeeded = succeededIds(toolResults);
        int turn = state.getTurnCount();

        // 仅当有文本或工具调用时才回填 AiMessage
        if (hasText || hasCalls) {
            AiMessage aiMsg = hasText
                    ? AiMessage.from(assistantText, pendingCalls)
                    : AiMessage.from(pendingCalls);
            jsonlStore.append(aiMsg, turn, succeeded);
        }

        if (toolResults != null) {
            for (ToolExecutionResult result : toolResults) {
                jsonlStore.append(ToolExecutionResultMessage.from(
                        result.toolCallId(), result.toolName(), result.content()), turn, succeeded);
            }
        }

        state.getPendingNudges().clear();
        state.getFormatCorrections().clear();
        state.setPendingToolCalls(null);
        state.setLastToolResults(null);
        state.setLastAssistantText(null);
    }

    /**
     * 仅当存在 pending 工具调用或结果时执行回填。
     * 用于 stop 流程中避免重复回填。
     */
    public void finalizeIfPending(SessionState state) {
        boolean hasPendingCalls = state.getPendingToolCalls() != null && !state.getPendingToolCalls().isEmpty();
        boolean hasToolResults = state.getLastToolResults() != null && !state.getLastToolResults().isEmpty();
        boolean hasText = state.getLastAssistantText() != null && !state.getLastAssistantText().isBlank();
        if (hasPendingCalls || hasToolResults || hasText) {
            finalizeAndAppend(state);
        }
    }

    private static Set<String> succeededIds(List<ToolExecutionResult> results) {
        Set<String> ids = new HashSet<>();
        if (results == null) return ids;
        for (ToolExecutionResult r : results) {
            if (r.success() && r.toolCallId() != null) ids.add(r.toolCallId());
        }
        return ids;
    }
}
