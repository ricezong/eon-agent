package cn.kong.eon.web.service;

import cn.kong.eon.web.dto.SessionListItem;

import java.util.List;
import java.util.Map;

/**
 * 会话管理服务。
 * <p>
 * 职责：索引查询/删除（含缓存失效）、账本回放与事件格式化。
 * 会话身份解析（新建/续接）已内联至 {@link ChatService}，不在此处。
 */
public interface SessionService {

    List<SessionListItem> listSessions(String userId);

    /** 硬删除会话。必须先失效缓存：否则缓存中的上下文会继续往已删除的目录写数据。 */
    boolean deleteSession(String userId, String sessionId);

    /** 回放账本，返回与实时 SSE 结构一致的事件列表（首帧为 session.start）。 */
    List<Map<String, Object>> getSessionEvents(String sessionId);
}
