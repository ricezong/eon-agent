package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * finish 工具：任务完成时调用。
 * 对应技术方案第 5.6 节。
 * 校验 Todo 全部完成 + 产出物存在性，通过后终止循环。
 */
public class FinishTool implements ToolExecutor {

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
                + "前置条件：所有 Todo 必须为 completed 或 cancelled 状态，否则调用会被拒绝。";
        return new ToolDescriptor(
                "finish",
                desc,
                cn.kong.eon.model.ToolPermission.RESTRICTED_WRITE,
                ToolDescriptor.buildSpec("finish", desc, props),
                new FinishTool()
        );
    }

    @Override
    public String execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        String summary = (String) arguments.get("summary");
        Boolean goalAchieved = (Boolean) arguments.get("goal_achieved");

        if (summary == null || summary.isBlank()) {
            return "[ERROR] finish 必须附带 summary";
        }

        // 校验 Todo 全部完成
        if (!context.todoStore().allCompleted()) {
            return "[ERROR] 存在未完成的 Todo，请先完成或取消所有任务再调用 finish";
        }

        state.setFinished(true);
        state.setFinishReason(goalAchieved != null && goalAchieved ? "GOAL_ACHIEVED" : "TASK_ENDED");
        state.setLastAssistantText(summary);

        StringBuilder sb = new StringBuilder();
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
