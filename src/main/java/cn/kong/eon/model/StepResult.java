package cn.kong.eon.model;

/**
 * 单步执行结果。
 * 对应技术方案第 3 节 StepResult。
 */
public record StepResult(StepAction action, String reason, String message) {

    public static StepResult continueLoop() {
        return new StepResult(StepAction.CONTINUE, null, null);
    }

    public static StepResult retry(String reason) {
        return new StepResult(StepAction.RETRY, reason, null);
    }

    public static StepResult stop(String reason, String message) {
        return new StepResult(StepAction.STOP, reason, message);
    }

    public enum StepAction {
        CONTINUE,   // 继续下一步
        RETRY,      // 重试当前步
        STOP        // 终止循环
    }
}
