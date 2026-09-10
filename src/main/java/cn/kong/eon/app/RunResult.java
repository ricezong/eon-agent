package cn.kong.eon.app;

/**
 * 对话运行结果。
 *
 * @param content   Agent 最终输出文本
 * @param sessionId 会话 ID（新建时返回新 ID，恢复时返回原 ID）
 */
public record RunResult(String content, String sessionId) {}
