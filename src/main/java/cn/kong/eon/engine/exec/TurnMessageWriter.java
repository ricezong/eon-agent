package cn.kong.eon.engine.exec;

import cn.kong.eon.runtime.RunContext;
import cn.kong.eon.runtime.TurnScope;
import cn.kong.eon.tool.model.ToolCallRecord;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 消息回填器。将 AI 消息和工具结果回填到账本，清理轮次级临时状态。
 * 工具结果以原始输出回填，落盘与格式化交给入站管线。
 */
@Component
public class TurnMessageWriter {

    /** 回填 AI 消息和工具结果，清理轮次级临时状态。 */
    public void flush(RunContext r) {
        TurnScope turn = r.turn();
        String assistantText = turn.assistantText();
        List<ToolCallRecord> toolResults = turn.toolResults();
        // 带 tool_calls 的 AiMessage 必须紧跟对应的工具结果，所以只有真正派发过工具才写 tool_calls；
        // 本轮在派发前就退出（PostModel/PreTool 拦截、用户中断）时只留正文，避免账本出现孤立 tool_calls。
        boolean dispatched = toolResults != null;
        List<ToolExecutionRequest> committedCalls = dispatched ? turn.pendingToolCalls() : List.of();
        boolean hasText = assistantText != null && !assistantText.isBlank();
        boolean hasCalls = !committedCalls.isEmpty();

        // 无正文且没有已执行的工具调用，本轮没有可留存的内容
        if (!hasText && !hasCalls) {
            turn.setPendingToolCalls(null);
            turn.setToolResults(null);
            turn.setAssistantText(null);
            return;
        }

        Set<String> succeeded = succeededIds(toolResults);
        AiMessage aiMsg = buildAiMessage(assistantText, committedCalls, hasText, hasCalls, turn.thinking());
        r.session().ledger().append(aiMsg, succeeded);

        if (toolResults != null) {
            for (ToolCallRecord result : toolResults) {
                ToolExecutionResultMessage toolResultMsg = ToolExecutionResultMessage.from(
                        result.toolCallId(), result.toolName(), result.content());
                r.session().ledger().append(toolResultMsg, succeeded, result.toolResultView());
            }
        }

        turn.setPendingToolCalls(null);
        turn.setToolResults(null);
        turn.setAssistantText(null);
        turn.setThinking(null);
    }

    /** 构建 AiMessage，携带 thinking 用于账本持久化。 */
    private static AiMessage buildAiMessage(String text, List<ToolExecutionRequest> calls,
                                            boolean hasText, boolean hasCalls, String thinking) {
        var builder = AiMessage.builder();
        if (hasText) {
            builder.text(text);
        }
        if (hasCalls) {
            builder.toolExecutionRequests(calls);
        }
        if (thinking != null && !thinking.isBlank()) {
            builder.thinking(thinking);
        }
        return builder.build();
    }

    /** 从工具执行结果中提取成功的调用 id 集合。 */
    private static Set<String> succeededIds(List<ToolCallRecord> results) {
        Set<String> ids = new HashSet<>();
        if (results == null) return ids;
        for (ToolCallRecord r : results) {
            if (r.success() && r.toolCallId() != null) {
                ids.add(r.toolCallId());
            }
        }
        return ids;
    }
}
