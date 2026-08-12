package cn.kong.eon.model;

/**
 * Todo 任务状态机。
 * pending -> in_progress -> completed
 *             |      |
 *             v      v
 *           blocked -> in_progress
 * cancelled <-> reactivate
 */
public enum TodoStatus {
    PENDING("⏸️", "待办"),
    IN_PROGRESS("🔄", "进行中"),
    COMPLETED("✅", "已完成"),
    BLOCKED("🚫", "阻塞"),
    CANCELLED("❌", "已取消");

    private final String icon;
    private final String label;

    TodoStatus(String icon, String label) {
        this.icon = icon;
        this.label = label;
    }

    public String icon() { return icon; }
    public String label() { return label; }
}
