package cn.kong.eon.web.dto;

/**
 * 对话请求。用户标识由 {@code X-User-Id} 请求头承载，不在请求体里重复。
 *
 * @param sessionId      会话 ID，为空时自动创建新会话
 * @param message        用户消息
 * @param kbId           知识库 ID，为空时跳过知识库检索（预留）
 * @param modelId        对话模型配置 ID，为空时使用默认模型（预留）
 * @param retryMessageId 重试时标记覆盖的旧 assistant 消息 ID，为空表示正常新对话（预留）
 */
public record ChatRequest(
        String sessionId,
        String message,
        Long kbId,
        Long modelId,
        Long retryMessageId
) {}
