package cn.kong.eon.runtime;

/**
 * 会话状态。用于 {@link SessionScope} 的并发互斥与缓存过期策略判定。
 */
public enum SessionLifecycle {
    /** 空闲：可被新任务获取 */
    IDLE,
    /** 执行中：同会话再来的请求按 busyPolicy 处理 */
    RUNNING,
    /** 已关闭：实例不再可用，需重新装配 */
    CLOSED
}
