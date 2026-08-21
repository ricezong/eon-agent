package cn.kong.eon.agent.profile;

import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 策略路由器。零 LLM 调用，基于 todoBeenUsed 标志判断：
 *   - 未调用 todo_write → SIMPLE
 *   - 已调用 todo_write → TASK（TodoNavigator 激活）
 * 单向升级，不可降级。
 *
 * 当前 SIMPLE/TASK 两者对工具挂载和上下文构建完全相同，
 * TASK profile 的唯一效果是激活 TodoNavigatorHook。
 * 保留 PolicyRouter 是为未来 SIMPLE/TASK 差异化行为预留扩展点。
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
