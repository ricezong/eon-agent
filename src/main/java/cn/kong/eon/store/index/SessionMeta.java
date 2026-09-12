package cn.kong.eon.store.index;

import java.time.Instant;

public record SessionMeta(
        String sessionId,
        String title,
        Instant lastActivityAt,
        long messageCount
) {}
