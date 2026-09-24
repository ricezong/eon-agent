package cn.kong.eon.api.dto;

/** 会话列表项。 */
public record SessionListItem(
        int index,
        String sessionId,
        String title,
        long messageCount,
        String lastActivityAt
) {
}
