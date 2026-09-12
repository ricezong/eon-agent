package cn.kong.eon.web.service;

import cn.kong.eon.web.dto.SessionListItem;

import java.util.List;
import java.util.Map;

/**
 * 会话管理服务。
 * <p>
 * 职责：会话索引查询/删除（含缓存失效）、账本回放与事件格式化。不负责执行编排。
 */
public interface SessionService {

    List<SessionListItem> listSessions(String userId);

    /**
     * 硬删除会话。固定顺序：先失效缓存 → 再删索引 → 再删目录。
     * 顺序不可颠倒，否则缓存中的上下文会继续往已删除的目录写数据。
     */
    boolean deleteSession(String userId, String sessionId);

    /** 回放账本，返回与实时 SSE 结构一致的事件列表。 */
    List<Map<String, Object>> getSessionEvents(String sessionId);
}
