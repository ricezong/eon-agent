package cn.kong.eon.agent.hook;

/**
 * 停止原因。携带终止类别和可读消息，用于终止时展示给用户。
 */
public final class StopReason {

    private final StopCategory category;
    private final String message;

    public StopReason(StopCategory category, String message) {
        this.category = category;
        this.message = message;
    }

    public StopCategory getCategory() {
        return category;
    }

    public String getMessage() {
        return message;
    }
}
