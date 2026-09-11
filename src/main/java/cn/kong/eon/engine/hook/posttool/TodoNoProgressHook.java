package cn.kong.eon.engine.hook.posttool;

import cn.kong.eon.engine.hook.Hook;
import cn.kong.eon.engine.hook.HookResult;
import cn.kong.eon.runtime.RunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Todo 无进展检测（PostTool, order=100）。
 * todo_write 成功后，滑动窗口比对 Todo 快照，连续 N 步无变化则注入 nudge 警告。
 * <p>
 * 独立于快照开关——即使 {@code snapshot_enabled=false}，无进展检测仍然工作。
 * <p>
 * 无状态：滑动窗口外置到 {@code r.task().progressTracker()}（每次 run 独立实例），
 * 删掉原先的 {@code reset()}，避免多会话并发时快照队列互相污染。
 */
@Component
public class TodoNoProgressHook implements Hook.PostToolHook {
    private static final Logger log = LoggerFactory.getLogger(TodoNoProgressHook.class);

    private static final String WARN_MSG = "连续 %s 步 Todo 无变化，请检查是否陷入循环";

    @Override
    public String name() {
        return "TodoNoProgressHook";
    }

    @Override
    public HookResult afterToolExecution(RunContext r, String toolName, boolean success) {
        if (!"todo_write".equals(toolName) || !success) return HookResult.ok();

        String snapshot = r.session().todoStore().getAll().toString();
        int windowSize = r.task().progressTracker().windowSize();
        int stepsWithoutProgress = r.task().progressTracker().pushAndCheck(snapshot);

        if (stepsWithoutProgress >= 2) {
            log.warn("[TodoNoProgress] 无进展: 连续 {} 个窗口（{} 步）未变化", stepsWithoutProgress, windowSize);
            r.task().addNudge(String.format(WARN_MSG, windowSize * stepsWithoutProgress));
        }

        return HookResult.ok();
    }
}
