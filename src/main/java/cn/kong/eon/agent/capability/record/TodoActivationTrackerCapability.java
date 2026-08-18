package cn.kong.eon.agent.capability.record;

import cn.kong.eon.agent.capability.CapabilityModule;
import cn.kong.eon.agent.capability.CapabilityResult;
import cn.kong.eon.agent.capability.Layer;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Todo 激活状态追踪能力模块（RECORD 层，order=10）。
 * 检测 todo_write 调用成功后标记 state.todoBeenUsed=true，使 TodoNavigator 下一轮激活。
 */
public class TodoActivationTrackerCapability implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(TodoActivationTrackerCapability.class);

    private static final String TODO_WRITE_TOOL = "todo_write";

    @Override public String name() { return "TodoActivationTracker"; }
    @Override public boolean isActive(SessionState state) { return true; }
    @Override public Layer layer() { return Layer.RECORD; }
    @Override public int orderInLayer() { return 10; }

    @Override
    public CapabilityResult afterToolExecution(SessionState state, String toolName, boolean success) {
        if (TODO_WRITE_TOOL.equals(toolName) && success) {
            if (!state.hasTodoBeenUsed()) {
                state.setTodoBeenUsed(true);
                log.info("TodoNavigator activated: todo_write called");
            }
        }
        return CapabilityResult.ok();
    }
}
