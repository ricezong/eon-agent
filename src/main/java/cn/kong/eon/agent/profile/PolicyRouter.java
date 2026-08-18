package cn.kong.eon.agent.profile;

import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 策略路由器。零 LLM 调用，基于 todoBeenUsed 标志判断：
 *   - 未调用 todo_write → SIMPLE（两阶段懒加载）
 *   - 已调用 todo_write → TASK（全量工具 Schema）
 * 单向升级，不可降级。
 */
public class PolicyRouter {
    private static final Logger log = LoggerFactory.getLogger(PolicyRouter.class);

    public RequestProfile route(SessionState state) {
        if (state.hasTodoBeenUsed()) {
            log.debug("Profile: TASK (todo_write used)");
            return RequestProfile.TASK;
        }
        log.debug("Profile: SIMPLE (default)");
        return RequestProfile.SIMPLE;
    }
}
