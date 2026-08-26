package cn.kong.eon.agent.support;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolExecutionResult;
import cn.kong.eon.store.JsonlStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.List;

/**
 * 消息回填器。将 AI 消息和工具结果回填到 JSONL transcript，清理会话临时状态，
 * 在 stop 流程中检查并回填 pending 消息。
 */
public class MessageFinalizer {
    private final JsonlStore jsonlStore;

    public MessageFinalizer(JsonlStore jsonlStore) {
        this.jsonlStore = jsonlStore;
    }

    /**
     * 回填 AI 消息和工具结果到 JSONL，清理临时状态。
     */
    public void finalizeAndAppend(TurnRecord rec, SessionState state) {
        String assistantText = state.getLastAssistantText();
        var pendingCalls = state.getPendingToolCalls();
        boolean hasText = assistantText != null && !assistantText.isBlank();
        boolean hasCalls = pendingCalls != null && !pendingCalls.isEmpty();

        // Only append AiMessage if there is text or tool calls to record
        if (hasText || hasCalls) {
            AiMessage aiMsg = hasText
                    ? AiMessage.from(assistantText, pendingCalls)
                    : AiMessage.from(pendingCalls);
            jsonlStore.append(aiMsg);
        }

        List<ToolExecutionResult> toolResults = state.getLastToolResults();
        if (toolResults != null) {
            for (ToolExecutionResult result : toolResults) {
                jsonlStore.append(ToolExecutionResultMessage.from(
                        result.toolCallId(), result.toolName(), result.content()));
            }
        }

        state.getPendingNudges().clear();
        state.getFormatCorrections().clear();
        state.setPendingToolCalls(null);
        state.setLastToolResults(null);
    }

    /**
     * 仅当存在 pending 工具调用或结果时执行回填。
     * 用于 stop 流程中避免重复回填。
     */
    public void finalizeIfPending(TurnRecord rec, SessionState state) {
        boolean hasPendingCalls = state.getPendingToolCalls() != null && !state.getPendingToolCalls().isEmpty();
        boolean hasToolResults = state.getLastToolResults() != null && !state.getLastToolResults().isEmpty();
        boolean hasText = state.getLastAssistantText() != null && !state.getLastAssistantText().isBlank();
        if (hasPendingCalls || hasToolResults || hasText) {
            finalizeAndAppend(rec, state);
        }
    }
}
