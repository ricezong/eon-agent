package cn.kong.eon.agent.capability.render;

import cn.kong.eon.agent.capability.CapabilityModule;
import cn.kong.eon.agent.capability.CapabilityResult;
import cn.kong.eon.agent.capability.Layer;
import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.TodoItem;
import cn.kong.eon.store.InsightsStore;
import cn.kong.eon.store.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Todo 导航渲染能力模块。
 *
 * <p>属于 {@link Layer#RENDER} 渲染层，orderInLayer=20（RENDER 层内第二执行，在 NudgeRenderer 之后）。
 * 按需激活：LLM 调用 todo_write 后激活（由 {@code TodoActivationTrackerCapability} 标记）。</p>
 *
 * <h3>职责</h3>
 * <p>仅在 {@link #beforeModelCall} 中渲染 Navigator（用户请求 + Todo 列表 + Insights）到
 * ContextBuilder，由 build() 统一注入为独立消息。</p>
 *
 * <h3>拆分说明</h3>
 * <p>原 {@code TodoNavigator} 同时承担"渲染"（beforeModelCall）和"激活标记"
 * （afterToolExecution 检测 todo_write 调用）两个职责。重构后拆分为：</p>
 * <ul>
 *   <li>{@code TodoNavigatorCapability}（本类，RENDER 层）：仅负责渲染。</li>
 *   <li>{@code TodoActivationTrackerCapability}（RECORD 层）：仅负责检测 todo_write 调用并标记激活。</li>
 * </ul>
 *
 * <h3>执行流程</h3>
 * <ol>
 *   <li>第 N 轮：模型调用 todo_write。</li>
 *   <li>第 N 轮 afterToolExecution：{@code TodoActivationTrackerCapability} 标记
 *       {@code state.todoBeenUsed=true}。</li>
 *   <li>第 N+1 轮 beforeModelCall：本类 {@link #isActive} 返回 true，开始渲染 Navigator。</li>
 * </ol>
 *
 * <h3>排序说明</h3>
 * <p>本类位于 RENDER 层（order=3），{@code TodoActivationTrackerCapability} 位于 RECORD 层（order=5）。
 * 但两者在不同 Hook 点执行（本类在 beforeModelCall，Tracker 在 afterToolExecution），
 * 不存在同 Hook 内的排序冲突，跨层排序仅用于同 Hook 内的模块间顺序。</p>
 */
public class TodoNavigatorCapability implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(TodoNavigatorCapability.class);

    private final TodoStore todoStore;
    private final InsightsStore insightsStore;

    public TodoNavigatorCapability(TodoStore todoStore, InsightsStore insightsStore) {
        this.todoStore = todoStore;
        this.insightsStore = insightsStore;
    }

    @Override
    public String name() { return "TodoNavigator"; }

    @Override
    public boolean isActive(SessionState state) {
        // LLM 调用过 todo_write 后激活（由 TodoActivationTrackerCapability 标记）
        return state.hasTodoBeenUsed();
    }

    @Override
    public Layer layer() { return Layer.RENDER; }

    @Override
    public int orderInLayer() { return 20; }

    @Override
    public CapabilityResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        StringBuilder sb = new StringBuilder();

        // Pinned: 用户原始请求（永不裁剪）
        sb.append("## [Pinned] 用户请求\n").append(state.getUserOriginalInput()).append("\n\n");

        // Pinned: 当前任务清单
        sb.append("## [Pinned] 当前任务清单\n");
        List<TodoItem> todos = todoStore.getAll();
        if (todos.isEmpty()) {
            sb.append("（暂无任务）\n");
        } else {
            for (TodoItem t : todos) {
                sb.append(t.toString()).append("\n");
            }
        }
        sb.append("\n");

        // Insights 滚动区
        List<String> insights = insightsStore.getAll();
        if (!insights.isEmpty()) {
            sb.append("## [Insights] 关键发现（最新在前）\n");
            int idx = 1;
            for (String insight : insights) {
                sb.append(idx++).append(". ").append(insight).append("\n");
            }
        }

        ctx.setNavigator(sb.toString());
        log.debug("Navigator rendered: {} chars", sb.length());

        return CapabilityResult.ok();
    }
}
