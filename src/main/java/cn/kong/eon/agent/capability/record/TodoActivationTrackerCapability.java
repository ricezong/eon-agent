package cn.kong.eon.agent.capability.record;

import cn.kong.eon.agent.capability.CapabilityModule;
import cn.kong.eon.agent.capability.CapabilityResult;
import cn.kong.eon.agent.capability.Layer;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Todo 激活状态追踪能力模块。
 *
 * <p>属于 {@link Layer#RECORD} 记录层，orderInLayer=10（RECORD 层内先于 CheckpointManager 执行）。
 * 始终激活。在 {@link #afterToolExecution} 中检测 todo_write 调用，标记
 * {@code state.todoBeenUsed=true}，使 {@code TodoNavigatorCapability} 在下一轮激活。</p>
 *
 * <h3>职责</h3>
 * <p>仅负责"激活标记"这一状态记录职责，不涉及任何渲染逻辑。</p>
 *
 * <h3>拆分说明</h3>
 * <p>原 {@code TodoNavigator} 的 {@code afterToolExecution} 中包含"检测到 todo_write 调用后
 * 标记激活"的逻辑。该逻辑本质是状态记录（属于 RECORD 层），而非渲染（RENDER 层），
 * 因此拆分到本类。</p>
 *
 * <h3>执行流程</h3>
 * <ol>
 *   <li>第 N 轮：模型调用 todo_write。</li>
 *   <li>第 N 轮 afterToolExecution：本类检测到 todo_write 调用成功，
 *       标记 {@code state.todoBeenUsed=true}。</li>
 *   <li>第 N+1 轮 beforeModelCall：{@code TodoNavigatorCapability.isActive} 返回 true，
 *       开始渲染 Navigator。</li>
 * </ol>
 *
 * <h3>排序说明</h3>
 * <p>本类位于 RECORD 层（order=5），orderInLayer=10，先于同层的
 * {@code CheckpointManager}（orderInLayer=100）执行。
 * 这保证"激活标记"先于"Checkpoint 保存"——若 todo_write 触发 Checkpoint 保存，
 * 保存时 todoBeenUsed 已为 true，Checkpoint 状态完整。</p>
 *
 * <p>本类与 {@code TodoNavigatorCapability}（RENDER 层）在不同 Hook 点执行，
 * 不存在同 Hook 内的排序冲突。</p>
 */
public class TodoActivationTrackerCapability implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(TodoActivationTrackerCapability.class);

    /** 触发激活的工具名。 */
    private static final String TODO_WRITE_TOOL = "todo_write";

    @Override
    public String name() { return "TodoActivationTracker"; }

    @Override
    public boolean isActive(SessionState state) {
        // 始终激活：需要监听每一轮的 todo_write 调用
        return true;
    }

    @Override
    public Layer layer() { return Layer.RECORD; }

    @Override
    public int orderInLayer() { return 10; }

    @Override
    public CapabilityResult afterToolExecution(SessionState state, String toolName, boolean success) {
        // 检测到 todo_write 调用成功，标记激活
        if (TODO_WRITE_TOOL.equals(toolName) && success) {
            if (!state.hasTodoBeenUsed()) {
                state.setTodoBeenUsed(true);
                log.info("TodoNavigator activated: todo_write called");
            }
        }
        return CapabilityResult.ok();
    }
}
