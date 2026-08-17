package cn.kong.eon.agent.profile;

import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 策略路由器。
 *
 * <h3>职责</h3>
 * 在 Core Loop 第一步执行，为当前请求分配 Profile：
 * <ul>
 *   <li>{@code SIMPLE}：默认模式，始终注入 tool_catalog（名称+摘要），
 *       不挂载完整工具 Schema。模型从 catalog 中选择需要的工具后，
 *       下一轮按声明的工具名挂载完整 Schema（两阶段懒加载）。</li>
 *   <li>{@code TASK}：LLM 已调用过 todo_write 后自动升级，全量挂载工具 Schema，
 *       TodoNavigator 激活。</li>
 * </ul>
 *
 * <h3>设计特点</h3>
 * <ul>
 *   <li>零 LLM 调用：基于 todoBeenUsed 标志判断，零延迟</li>
 *   <li>自动升级：LLM 调 todo_write 后自动升级为 TASK，单向不可降级</li>
 *   <li>不再使用关键词匹配做意图识别：工具可见性由两阶段懒加载机制保证</li>
 * </ul>
 */
public class PolicyRouter {
    private static final Logger log = LoggerFactory.getLogger(PolicyRouter.class);

    /**
     * 为当前请求分配 Profile。
     *
     * @return SIMPLE（默认）或 TASK（todo_write 调用后升级）
     */
    public RequestProfile route(SessionState state) {
        // 如果已经调用过 todo_write，升级为 TASK
        if (state.hasTodoBeenUsed()) {
            log.debug("Profile: TASK (todo_write used)");
            return RequestProfile.TASK;
        }

        // 默认 SIMPLE
        log.debug("Profile: SIMPLE (default)");
        return RequestProfile.SIMPLE;
    }
}
