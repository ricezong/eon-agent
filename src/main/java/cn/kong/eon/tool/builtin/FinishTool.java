package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.TerminationFormatter;
import cn.kong.eon.model.TodoItem;
import cn.kong.eon.model.TodoStatus;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * finish 工具：任务总结与循环终止。
 *
 * 调用时机：
 *   1. 任务正常完成 — 用户请求的工作已做完
 *   2. 用户明确要求总结 — 用户说"总结一下"/"汇报进度"等
 *   3. 任务被中断需要收尾 — 收到预算告警/熔断/循环检测等 stop nudge 后
 *
 * finish 是唯一的循环退出出口（除了硬终止）。被中断时必须调用 finish 总结当前进度。
 */
public class FinishTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(FinishTool.class);

    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("summary", Map.of(
                "type", "string",
                "description", "任务总结。必须包含：做了什么、结果如何。如果任务被中断，还需说明中断原因、当前进度和未完成事项。",
                "required", true
        ));
        props.put("goal_achieved", Map.of(
                "type", "boolean",
                "description", "目标是否完全达成。true=任务正常完成；false=任务未完成或被中断（需在 summary 中说明原因和剩余工作）",
                "required", true
        ));
        props.put("pending_work", Map.of(
                "type", "string",
                "description", "未完成的工作描述（任务被中断或部分完成时必填）。说明还有哪些工作需要做。"
        ));
        props.put("follow_up_suggestions", Map.of(
                "type", "array",
                "description", "可选的后续建议（如重新尝试、换种方式等）"
        ));
        String desc = "总结任务并终止循环。适用场景：1) 任务正常完成；2) 用户要求总结；3) 收到中断通知（预算超限/熔断/循环检测）时立即调用并总结当前进度。"
                + "如果存在未完成的 Todo，会附上进度统计。";
        return new ToolDescriptor(
                "finish",
                desc,
                ToolPermission.RESTRICTED_WRITE,
                ToolDescriptor.buildSpec("finish", desc, props),
                new FinishTool()
        );
    }

    @Override
    public String execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        String summary = (String) arguments.get("summary");
        if (summary == null || summary.isBlank()) {
            return "[ERROR] finish 必须附带 summary, 请基于当前对话上下文进行总结";
        }

        boolean goalAchieved = Boolean.TRUE.equals(arguments.get("goal_achieved"));
        String pendingWork = (String) arguments.get("pending_work");
        List<String> followUps = extractFollowUps(arguments.get("follow_up_suggestions"));

        // 设置结束状态
        state.setFinished(true);
        state.setFinishReason(goalAchieved ? "GOAL_ACHIEVED" : "TASK_ENDED");
        state.setLastAssistantText(summary);

        List<TodoItem> todos = context.todoStore().getAll();
        log.info("Finish called: goalAchieved={}, todos={}/{}, tokens={}",
                goalAchieved,
                todos.isEmpty() ? 0 : todos.stream().filter(t -> t.getStatus() == TodoStatus.COMPLETED).count(),
                todos.size(),
                state.getUsageAccum().getTotalTokens());

        String stopReasonMessage = state.isStopRequested()
                ? state.getStopState().getReason().getMessage() : null;

        return new TerminationFormatter().formatFinish(
                summary, goalAchieved, pendingWork, followUps,
                stopReasonMessage, todos,
                state.getTurnCount(), state.getUsageAccum().getTotalTokens());
    }

    @SuppressWarnings("unchecked")
    private List<String> extractFollowUps(Object raw) {
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
