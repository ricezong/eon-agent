package cn.kong.eon.agent.profile;

import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 策略路由器。
 *
 * <h3>职责</h3>
 * 在 Core Loop 第一步执行，为当前请求分配 Profile：
 * <ul>
 *   <li>LIGHT_CHAT：输入短 + 无工具触发词 → 不注入工具 Schema</li>
 *   <li>ASSISTED：输入含工具触发词（搜索/读取/下载/查询）→ 只注入网络搜索相关工具</li>
 *   <li>TASK_MULTI：LLM 已调用过 todo_write → 全量注入工具 Schema</li>
 * </ul>
 *
 * <h3>设计特点</h3>
 * <ul>
 *   <li>零 LLM 调用：基于关键词匹配，零延迟</li>
 *   <li>自动升级：LLM 调 todo_write 后自动升级为 TASK_MULTI</li>
 *   <li>不注入 System Prompt：Profile 只用于决定 Schema 注入，不告知 LLM</li>
 * </ul>
 */
public class PolicyRouter {
    private static final Logger log = LoggerFactory.getLogger(PolicyRouter.class);

    /** 工具触发词（命中任一即升级为 ASSISTED） */
    private static final Set<String> TOOL_TRIGGERS = Set.of(
            "搜索", "查找", "查询", "读取", "下载", "获取", "分析","调研",
            "search", "read", "download", "fetch", "find", "query"
    );

    /** 简单聊天的最大输入长度（超过则可能是任务） */
    private static final int LIGHT_CHAT_MAX_LENGTH = 50;

    /**
     * 为当前请求分配 Profile。
     */
    public RequestProfile route(SessionState state) {
        // 如果已经调用过 todo_write，升级为 TASK_MULTI
        if (state.hasTodoBeenUsed()) {
            log.debug("Profile: TASK_MULTI (todo_write used)");
            return RequestProfile.TASK_MULTI;
        }

        String input = state.getUserOriginalInput();
        if (input == null || input.isBlank()) {
            return RequestProfile.LIGHT_CHAT;
        }

        // 检查工具触发词
        String lowerInput = input.toLowerCase();
        for (String trigger : TOOL_TRIGGERS) {
            if (lowerInput.contains(trigger)) {
                log.debug("Profile: ASSISTED (trigger word: {})", trigger);
                return RequestProfile.ASSISTED;
            }
        }

        // 短输入且无触发词 → LIGHT_CHAT
        if (input.length() <= LIGHT_CHAT_MAX_LENGTH) {
            log.debug("Profile: LIGHT_CHAT (short input, no trigger)");
            return RequestProfile.LIGHT_CHAT;
        }

        // 长输入但无触发词，可能是需要工具的复杂请求
        log.debug("Profile: ASSISTED (long input)");
        return RequestProfile.ASSISTED;
    }
}
