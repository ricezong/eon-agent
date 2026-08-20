package cn.kong.eon.agent.profile;

import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 策略路由器。零 LLM 调用，基于 todoBeenUsed 标志判断：
 *   - 未调用 todo_write → SIMPLE
 *   - 已调用 todo_write → TASK（TodoNavigator 激活）
 * 单向升级，不可降级。
 */
public class PolicyRouter {
    private static final Logger log = LoggerFactory.getLogger(PolicyRouter.class);
    private boolean wasTask = false;

    public RequestProfile route(SessionState state) {
        if (state.hasTodoBeenUsed()) {
            if (!wasTask) {
                log.info("[Profile] upgraded: SIMPLE -> TASK (todo_write used)");
                wasTask = true;
            }
            return RequestProfile.TASK;
        }
        return RequestProfile.SIMPLE;
    }
}
