package cn.kong.eon.agent;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.hook.AbortCategory;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.profile.PolicyRouter;
import cn.kong.eon.agent.profile.RequestProfile;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolExecutionResult;
import cn.kong.eon.store.JsonlStore;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolRegistry;
import cn.kong.eon.tool.ToolResultRenderer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Agent 单入口统一引擎。
 *
 * Core Loop: 路由 → 组装上下文 → PreModel → build → 调用 LLM → 解析 → 返回/扩展
 * Extension Loop: PreTool → 执行工具 → PostTool → 回填 → 回到 Core Loop
 *
 * System Prompt 完全冻结（basePrompt），tool_catalog 作为独立消息注入，保证 KV Cache 前缀稳定。
 * Profile 两档：SIMPLE（默认）、TASK（todo_write 后升级，TodoNavigator 激活）。两者均走两阶段懒加载。
 *
 * Hook 调度：按执行阶段分组（PreModel/PostModel/PreTool/PostTool），用 instanceof 过滤，
 * 同阶段内按 order() 升序执行。
 */
public class EonAgent {
    private static final Logger log = LoggerFactory.getLogger(EonAgent.class);

    private static final String ENABLE_TOOLS = "enable_tools";

    private final AgentConfig config;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ToolResultRenderer resultRenderer;
    private final JsonlStore jsonlStore;
    private final String basePrompt;
    private final PolicyRouter policyRouter;
    private final List<Hook> hooks;
    private final ToolContext toolContext;

    public EonAgent(AgentConfig config,
                    LlmClient llmClient,
                    ToolRegistry toolRegistry,
                    ToolResultRenderer resultRenderer,
                    JsonlStore jsonlStore,
                    String basePrompt,
                    ToolContext toolContext) {
        this.config = config;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.resultRenderer = resultRenderer;
        this.jsonlStore = jsonlStore;
        this.basePrompt = basePrompt;
        this.policyRouter = new PolicyRouter();
        this.hooks = new ArrayList<>();
        this.toolContext = toolContext;
    }

    public void addHook(Hook hook) {
        hooks.add(hook);
        log.debug("Hook added: {}", hook.name());
    }

    public int getHookCount() {
        return hooks.size();
    }

    /** 按 order 升序排序，每次调用创建新列表。 */
    private List<Hook> getSortedHooks() {
        List<Hook> sorted = new ArrayList<>(hooks);
        sorted.sort(Comparator.comparingInt(Hook::order));
        return sorted;
    }

    /** 运行 Agent 主循环。 */
    public String run(SessionState state) {
        log.info("=== EonAgent 启动 === session={}", state.getSessionId());
        log.info("用户请求: {}", state.getUserOriginalInput());

        // 重置懒加载状态
        state.setPendingToolMounts(null);

        jsonlStore.append(UserMessage.from(state.getUserOriginalInput()));

        while (state.getTurnCount() < config.getLoop().maxSteps) {
            state.incrementTurn();

            RequestProfile profile = policyRouter.route(state);
            log.info("--- Turn {} [{}] ---", state.getTurnCount(), profile);

            try {
                // ===== Core Loop =====

                // 1. 组装上下文
                ContextBuilder ctx = buildContext(state, profile);

                // 2. PreModel 阶段
                List<Hook> sortedHooks = getSortedHooks();
                for (Hook hook : sortedHooks) {
                    if (!hook.isActive(state)) continue;
                    if (hook instanceof Hook.PreModelHook pmh) {
                        HookResult r = pmh.beforeModelCall(state, ctx);
                        if (r.isAbort()) {
                            return formatAbort(r);
                        }
                    }
                }

                // 3. 构建最终 messages
                List<ChatMessage> messages = ctx.build();
                state.setCurrentMessages(messages);

                // 4. 获取工具 Schema（按 Profile + 两阶段懒加载）
                List<ToolSpecification> tools = getToolsForProfile(state, profile);

                // 5. 调用模型
                LlmResponse response = llmClient.chat(messages, tools);
                state.setLastResponse(response);
                state.getUsageAccum().add(response.usage());
                log.info("Model called OK: tokens={}", response.usage());

                // 6. 解析输出
                String thought = response.aiMessage().text() != null ? response.aiMessage().text() : "";
                state.setLastAssistantText(thought);

                List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();
                if (requests == null || requests.isEmpty()) {
                    // 已声明工具但未调用：提醒模型
                    if (state.getPendingToolMounts() != null) {
                        state.addFormatCorrection(
                                "你已获得工具的完整调用参数但未调用。如果任务需要工具，请直接调用；如果不需要，请直接回答。");
                        finalizeAndAppend(state);
                        continue;
                    }
                    // 聊天结束
                    finalizeAndAppend(state);
                    return thought;
                }

                // 校验工具是否存在
                for (ToolExecutionRequest req : requests) {
                    if (!toolRegistry.contains(req.name())) {
                        state.getFormatCorrections().add("工具 " + req.name() + " 不存在，请使用可用工具。");
                    }
                }
                state.setPendingToolCalls(requests);

                // 7. PostModel 阶段
                for (Hook hook : sortedHooks) {
                    if (!hook.isActive(state)) continue;
                    if (hook instanceof Hook.PostModelHook pmh) {
                        HookResult r = pmh.afterModelCall(state, response);
                        if (r.isAbort()) {
                            return formatAbort(r);
                        }
                    }
                }

                // ===== Extension Loop =====

                // 8. PreTool 阶段
                for (Hook hook : sortedHooks) {
                    if (!hook.isActive(state)) continue;
                    if (hook instanceof Hook.PreToolHook pth) {
                        HookResult r = pth.beforeToolExecution(state, requests);
                        if (r.isAbort()) {
                            return formatAbort(r);
                        }
                    }
                }

                // 9. 执行工具
                List<ToolExecutionResult> results = executeTools(state);

                // 10. PostTool 阶段
                for (int i = 0; i < requests.size(); i++) {
                    ToolExecutionRequest req = requests.get(i);
                    ToolExecutionResult result = results.get(i);
                    boolean success = !result.content().startsWith("[ERROR]");
                    for (Hook hook : sortedHooks) {
                        if (!hook.isActive(state)) continue;
                        if (hook instanceof Hook.PostToolHook pth) {
                            HookResult r = pth.afterToolExecution(state, req.name(), success);
                            if (r.isAbort()) {
                                return formatAbort(r);
                            }
                        }
                    }
                }

                // 11. 回填
                finalizeAndAppend(state);

                // finish 检测
                if (state.isFinished()) {
                    return state.getLastAssistantText();
                }

            } catch (Exception e) {
                log.error("Agent loop unexpected error: {}", e.getMessage(), e);
                return "执行异常: " + e.getMessage();
            }
        }

        log.warn("达到最大步数 {}，强制终止", config.getLoop().maxSteps);
        return "达到最大步数限制";
    }

    /** 格式化 Hook 的中断返回值。 */
    private String formatAbort(HookResult r) {
        AbortCategory category = r.getCategory();
        String reason = r.getReason();
        log.warn("Hook aborted: category={}, reason={}", category, reason);
        if (category == null) {
            return "执行中断: " + reason;
        }
        return category.getDisplayName() + ": " + reason;
    }

    /** 组装上下文。 */
    private ContextBuilder buildContext(SessionState state, RequestProfile profile) {
        ContextBuilder ctx = new ContextBuilder();
        ctx.setSystemPrompt(basePrompt);

        if (state.getCompressionState().getLastSummary() != null) {
            ctx.setSummary(state.getCompressionState().getLastSummary());
        }

        List<ChatMessage> transcript = jsonlStore.readAll();
        ctx.setTranscript(transcript);

        ctx.setToolCatalog(toolRegistry.getCatalogSummary());

        return ctx;
    }

    /**
     * 两阶段懒加载获取工具 Schema（SIMPLE / TASK 统一）。
     * 未声明：只挂载 enable_tools
     * 已声明：按声明的工具名挂载（排除 enable_tools 自身）
     */
    private List<ToolSpecification> getToolsForProfile(SessionState state, RequestProfile profile) {
        Set<String> mounts = state.getPendingToolMounts();
        if (mounts == null || mounts.isEmpty()) {
            // 第一轮：只挂载 enable_tools
            return toolRegistry.getSpecificationsByName(Set.of(ENABLE_TOOLS));
        }

        // 第二轮：挂载模型声明的工具（排除 enable_tools 自身）
        Set<String> realMounts = new LinkedHashSet<>(mounts);
        realMounts.remove(ENABLE_TOOLS);
        List<ToolSpecification> specs = toolRegistry.getSpecificationsByName(realMounts);
        log.info("Mounting tools (lazy load): {} -> {} specs", realMounts, specs.size());
        return specs;
    }

    /** 执行工具。 */
    private List<ToolExecutionResult> executeTools(SessionState state) {
        List<ToolExecutionRequest> requests = state.getPendingToolCalls();
        List<ToolExecutionResult> results = new ArrayList<>();

        for (ToolExecutionRequest req : requests) {
            Map<String, Object> args = parseArgs(req.arguments());
            String reason = (String) args.get("reason");

            String rawResult = toolRegistry.execute(req.name(), args, state, toolContext);

            // 拦截 enable_tools：从参数中提取工具名，设置 pendingToolMounts
            if (ENABLE_TOOLS.equals(req.name())) {
                Object toolsRaw = args.get("tools");
                if (toolsRaw instanceof List<?> list) {
                    Set<String> declaredTools = new LinkedHashSet<>();
                    for (Object item : list) {
                        String name = String.valueOf(item).trim();
                        if (toolRegistry.contains(name)) {
                            declaredTools.add(name);
                        } else {
                            log.warn("Declared tool not found: {}", name);
                        }
                    }
                    if (!declaredTools.isEmpty()) {
                        state.setPendingToolMounts(declaredTools);
                        log.info("Model declared tools via enable_tools: {}", declaredTools);
                    }
                }
            }

            String rendered = resultRenderer.render(req.name(), req.id(), reason, rawResult, state);

            results.add(ToolExecutionResult.of(req.id(), req.name(), rendered));
            log.info("Tool {} executed, result {} chars", req.name(), rendered.length());

            if ("finish".equals(req.name()) && state.isFinished()) {
                break;
            }
        }
        state.setLastToolResults(results);
        return results;
    }

    /** 回填 AI 消息和工具结果到 JSONL，清理临时状态。 */
    private void finalizeAndAppend(SessionState state) {
        AiMessage aiMsg = state.getLastAssistantText() != null && !state.getLastAssistantText().isBlank()
                ? AiMessage.from(state.getLastAssistantText(), state.getPendingToolCalls())
                : AiMessage.from(state.getPendingToolCalls());
        jsonlStore.append(aiMsg);

        List<ToolExecutionResult> toolResults = state.getLastToolResults();
        if (toolResults != null) {
            for (ToolExecutionResult result : toolResults) {
                jsonlStore.append(ToolExecutionResultMessage.from(
                        result.toolCallId(), result.toolName(), result.content()));
            }
        }

        state.getPendingNudges().clear();
        state.getFormatCorrections().clear();
        state.setPendingToolCalls(null);
        state.setLastToolResults(null);
    }

    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new ObjectMapper()
                    .readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse tool arguments: {}", json, e);
            return Map.of();
        }
    }
}
