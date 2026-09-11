package cn.kong.eon.web.dto;

/**
 * 对话请求。chat 接口唯一入参。
 *
 * @param sessionId      会话 ID，为空时自动创建新会话
 * @param message        用户消息
 * @param userId         用户标识
 * @param kbId           知识库 ID，为空时跳过知识库检索（预留）
 * @param modelId        对话模型配置 ID，为空时使用默认模型（预留）
 * @param retryMessageId 重试时标记覆盖的旧 assistant 消息 ID，为空表示正常新对话（预留）
 */
public record ChatRequest(
        String sessionId,
        String message,
        String userId,
        Long kbId,
        Long modelId,
        Long retryMessageId
) {}
