package cn.kong.eon.model;

import java.util.List;

/**
 * 终止输出格式化器。纯数据格式化，不依赖 store 或 agent 层。
 * 接收原始数据（原因文本、todo 列表、insights），输出结构化文本。
 */
public class TerminationFormatter {

    /** 硬终止输出：终止原因 + Todo 进度 + 待做任务 + 关键发现 + Token 消耗。 */
    public String formatForcedTermination(String stopCategory, String stopMessage,
                                           List<TodoItem> todos, List<String> insights,
                                           int turnCount, int totalTokens,
                                           int promptTokens, int completionTokens) {
        StringBuilder sb = new StringBuilder();
        appendTerminationHeader(sb, stopCategory, stopMessage);
        appendTodoProgress(sb, todos);
        appendInsights(sb, insights);
        appendTokenStats(sb, turnCount, totalTokens, promptTokens, completionTokens);
        return sb.toString();
    }

    /** finish 输出：summary + 中断原因(如有) + Todo 进度统计。 */
    public String formatFinish(String summary, boolean goalAchieved,
                               String pendingWork, List<String> followUps,
                               String stopReasonMessage,
                               List<TodoItem> todos,
                               int turnCount, int totalTokens) {
        StringBuilder sb = new StringBuilder();
        sb.append("[FINISH] 任务结束\n");
        sb.append("目标达成: ").append(goalAchieved).append("\n");
        sb.append("总结: ").append(summary).append("\n");

        if (stopReasonMessage != null) {
            sb.append("中断原因: ").append(stopReasonMessage).append("\n");
        }

        appendTodoProgressForFinish(sb, todos);

        if (pendingWork != null && !pendingWork.isBlank()) {
            sb.append("\n待做工作: ").append(pendingWork).append("\n");
        }
        if (followUps != null && !followUps.isEmpty()) {
            sb.append("后续建议:\n");
            for (String s : followUps) {
                sb.append("  - ").append(s).append("\n");
            }
        }

        sb.append("\n消耗: ").append(totalTokens)
                .append(" tokens, ").append(turnCount).append(" 轮\n");
        return sb.toString();
    }

    // ===== 内部方法 =====

    private void appendTerminationHeader(StringBuilder sb, String category, String message) {
        sb.append("════════════════════════════════════════\n");
        sb.append("  任务终止: ").append(category).append("\n");
        sb.append("  原因: ").append(message).append("\n");
        sb.append("════════════════════════════════════════\n\n");
    }

    private void appendTodoProgress(StringBuilder sb, List<TodoItem> todos) {
        if (todos == null || todos.isEmpty()) {
            sb.append("【任务进度】无 Todo 列表（任务未通过 todo_write 建立计划）\n");
            return;
        }
        sb.append("【任务进度】").append(countByStatus(todos, TodoStatus.COMPLETED)).append("/").append(todos.size())
                .append(" 已完成（").append(countByStatus(todos, TodoStatus.IN_PROGRESS)).append(" 进行中, ")
                .append(countByStatus(todos, TodoStatus.PENDING)).append(" 待办, ")
                .append(countByStatus(todos, TodoStatus.BLOCKED)).append(" 阻塞）\n\n");

        sb.append("【任务清单】\n");
        for (TodoItem t : todos) {
            sb.append("  ").append(t.toString()).append("\n");
        }
        sb.append("\n");

        List<TodoItem> pending = todos.stream()
                .filter(t -> t.getStatus() != TodoStatus.COMPLETED
                        && t.getStatus() != TodoStatus.CANCELLED)
                .toList();
        if (!pending.isEmpty()) {
            sb.append("【待做任务】\n");
            for (TodoItem t : pending) {
                sb.append("  - ").append(t.getContent());
                if (t.getStatus() == TodoStatus.BLOCKED && t.getBlockReason() != null) {
                    sb.append(" [阻塞原因: ").append(t.getBlockReason()).append("]");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
    }

    private void appendTodoProgressForFinish(StringBuilder sb, List<TodoItem> todos) {
        if (todos == null || todos.isEmpty()) return;

        sb.append("\n进度统计: ").append(countByStatus(todos, TodoStatus.COMPLETED)).append("/").append(todos.size())
                .append(" 完成 (").append(countByStatus(todos, TodoStatus.IN_PROGRESS)).append(" 进行中, ")
                .append(countByStatus(todos, TodoStatus.PENDING)).append(" 待办, ")
                .append(countByStatus(todos, TodoStatus.BLOCKED)).append(" 阻塞)\n");

        List<TodoItem> pending = todos.stream()
                .filter(t -> t.getStatus() != TodoStatus.COMPLETED
                        && t.getStatus() != TodoStatus.CANCELLED)
                .toList();
        if (!pending.isEmpty()) {
            sb.append("未完成任务:\n");
            for (TodoItem t : pending) {
                sb.append("  ").append(t.toString());
                if (t.getStatus() == TodoStatus.BLOCKED && t.getBlockReason() != null) {
                    sb.append(" [阻塞: ").append(t.getBlockReason()).append("]");
                }
                sb.append("\n");
            }
        }
    }

    private void appendInsights(StringBuilder sb, List<String> insights) {
        if (insights == null || insights.isEmpty()) return;
        sb.append("【关键发现】\n");
        int idx = 1;
        for (String insight : insights) {
            sb.append("  ").append(idx++).append(". ").append(insight).append("\n");
        }
        sb.append("\n");
    }

    private void appendTokenStats(StringBuilder sb, int turnCount, int totalTokens,
                                   int promptTokens, int completionTokens) {
        sb.append("【消耗统计】总 Token: ").append(totalTokens)
                .append(" (prompt=").append(promptTokens)
                .append(", completion=").append(completionTokens)
                .append(")\n");
        sb.append("执行轮次: ").append(turnCount).append("\n");
    }

    private long countByStatus(List<TodoItem> todos, TodoStatus status) {
        return todos.stream().filter(t -> t.getStatus() == status).count();
    }
}
