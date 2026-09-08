package cn.kong.eon.agent.hook.postmodel;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 截断检测（PostModel, order=5）。
 * finishReason=length 时注入截断提示 nudge，让下一轮重新调用工具。
 * 本 Hook 只负责注入 nudge，是否继续循环由 EonAgent 的控制流决定。
 */
public class TruncationHook implements Hook.PostModelHook {
    private static final Logger log = LoggerFactory.getLogger(TruncationHook.class);

    private static final String TRUNCATION_NUDGE = "上一轮输出因长度限制被截断，工具调用未完成。请重新调用工具，如果内容过长请分多次写入。";

    @Override
    public String name() {
        return "Truncation";
    }

    @Override
    public boolean active(SessionState state) {
        return true;
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public HookResult afterModelCall(SessionState state) {
        if (state.getLastResponse() == null) {
            return HookResult.ok();
        }
        if ("length".equalsIgnoreCase(state.getLastResponse().finishReason())) {
            state.addNudge(TRUNCATION_NUDGE);
            log.info("[Truncation] 输出被截断，已注入提示");
        }
        return HookResult.ok();
    }
}
