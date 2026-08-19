package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * finish 工具：任务完成时调用，终止 Agent 循环。
 * 存在未完成 Todo 时在结果中提醒但不阻断。
 */
public class FinishTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(FinishTool.class);

    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("summary", Map.of(
                "type", "string",
                "description", "任务完成总结，说明做了什么、结果如何",
                "required", true
        ));
        props.put("goal_achieved", Map.of(
                "type", "boolean",
                "description", "目标是否达成。false 表示任务无法完成（需在 summary 说明原因）",
                "required", true
        ));
        props.put("follow_up_suggestions", Map.of(
                "type", "array",
                "description", "可选的后续建议"
        ));
        String desc = "任务完成时调用，终止 Agent 循环。"
                + "如果存在未完成的 Todo，调用时会在结果中提醒但不阻断。";
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
        Boolean goalAchieved = (Boolean) arguments.get("goal_achieved");

        if (summary == null || summary.isBlank()) {
            return "[ERROR] finish 必须附带 summary, 请基于当前对话上下文进行总结";
        }

        StringBuilder sb = new StringBuilder();
        if (!context.todoStore().allCompleted()) {
            int pending = (int) context.todoStore().getAll().stream()
                    .filter(t -> t.getStatus() != cn.kong.eon.model.TodoStatus.COMPLETED
                            && t.getStatus() != cn.kong.eon.model.TodoStatus.CANCELLED)
                    .count();
            sb.append("[提醒] 存在 ").append(pending).append(" 个未完成的 Todo，建议确认是否需要继续。\n");
            log.warn("finish called with {} pending todos", pending);
        }

        state.setFinished(true);
        state.setFinishReason(goalAchieved != null && goalAchieved ? "GOAL_ACHIEVED" : "TASK_ENDED");
        state.setLastAssistantText(summary);

        sb.append("[FINISH] 任务结束\n");
        sb.append("目标达成: ").append(goalAchieved).append("\n");
        sb.append("总结: ").append(summary).append("\n");

        Object followUps = arguments.get("follow_up_suggestions");
        if (followUps instanceof java.util.List<?> list && !list.isEmpty()) {
            sb.append("后续建议:\n");
            for (Object s : list) {
                sb.append("  - ").append(s).append("\n");
            }
        }

        return sb.toString();
    }
}
