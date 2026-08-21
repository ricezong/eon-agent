package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.TodoItem;
import cn.kong.eon.model.TodoStatus;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.store.TodoStore;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * finish 工具：复杂任务的总结与循环终止。
 * 仅做状态控制（标记 finished），summary 由大模型生成并直接作为最终输出。
 * 简单问答或简单工具任务无需调用 finish，LLM 直接回复文本即可退出循环。
 */
public class FinishTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(FinishTool.class);

    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("summary", Map.of(
                "type", "string",
                "description", "任务总结。必须包含：做了什么、结果如何、当前进度。如果任务被中断，还需说明中断原因和未完成事项。",
                "required", true
        ));
        props.put("goal_achieved", Map.of(
                "type", "boolean",
                "description", "目标是否完全达成。true=任务正常完成；false=任务未完成或被中断",
                "required", true
        ));
        String desc = "总结任务并终止循环。summary 内容将作为最终输出返回给用户。"
                + "适用场景：1) 复杂任务（含 Todo）全部完成；2) 用户要求总结进度；3) 收到中断通知时立即调用。"
                + "注意：简单问答或简单工具任务无需调用 finish，直接回复文本即可结束。";
        return new ToolDescriptor(
                "finish",
                desc,
                ToolPermission.RESTRICTED_WRITE,
                ToolDescriptor.buildSpec("finish", desc, props),
                new FinishTool()
        );
    }

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        String summary = (String) arguments.get("summary");
        if (summary == null || summary.isBlank()) {
            return ToolOutcome.failure("finish 必须附带 summary, 请基于当前对话上下文进行总结");
        }

        boolean goalAchieved = Boolean.TRUE.equals(arguments.get("goal_achieved"));

        // 拦截：Todo 存在且未全部完成时，不允许 goal_achieved=true
        List<TodoItem> todos = context.todoStore().getAll();
        if (goalAchieved && !todos.isEmpty() && !context.todoStore().allCompleted()) {
            String pending = TodoStore.formatPending(todos);
            String progress = TodoStore.formatProgress(todos);
            log.warn("Finish blocked: goal_achieved=true but todos not all completed. {}", progress);
            return ToolOutcome.failure(
                    "Todo 尚未全部完成，不能标记 goal_achieved=true。\n"
                    + "当前进度: " + progress + "\n"
                    + "未完成任务:\n" + pending
                    + "请继续执行未完成的任务，或者将 goal_achieved 设为 false 并说明原因。");
        }

        // 设置结束状态
        state.setFinished(true);
        state.setFinishReason(goalAchieved ? "GOAL_ACHIEVED" : "TASK_ENDED");
        state.setLastAssistantText(summary);

        long completed = todos.stream()
                .filter(t -> t.getStatus() == TodoStatus.COMPLETED).count();
        int total = todos.size();
        log.info("Finish called: goalAchieved={}, todos={}/{}, tokens={}",
                goalAchieved, completed, total, state.getUsageAccum().getTotalTokens());

        // summary 直接作为最终输出，不再额外格式化
        return ToolOutcome.success(summary);
    }
}
