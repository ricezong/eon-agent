package cn.kong.eon.engine.hook.posttool;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.engine.hook.Hook;
import cn.kong.eon.engine.hook.HookResult;
import cn.kong.eon.runtime.RunContext;
import cn.kong.eon.store.todo.TodoItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话快照（PostTool, order=200）。
 * todo_write 成功后，将 todo 列表 + token 累计 + 压缩状态写入 state.json。
 * 仅在 {@code snapshot_enabled=true} 时激活。
 * <p>
 * 无状态：快照存储与 TodoStore 均从 {@code r.session()} 取。
 */
@Component
public class SessionSnapshotHook implements Hook.PostToolHook {
    private static final Logger log = LoggerFactory.getLogger(SessionSnapshotHook.class);

    private final AgentConfig config;

    public SessionSnapshotHook(AgentConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "SessionSnapshotHook";
    }

    @Override
    public boolean active(RunContext r) {
        return config.isSnapshotEnabled();
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public HookResult afterToolExecution(RunContext r, String toolName, boolean success) {
        if (!"todo_write".equals(toolName) || !success) return HookResult.ok();

        List<TodoItem> todos = r.session().todoStore().getAll();
        r.session().snapshotStore().save(todos, r.session().usageAccum(), r.session().compressionState());
        log.info("[SessionSnapshot] 已保存: todo={} 条, replayFrom={}",
                todos.size(), r.session().compressionState().getReplayFromSeq());

        return HookResult.ok();
    }
}
