package cn.kong.eon.agent;

import cn.kong.eon.agent.capability.CapabilityModule;
import cn.kong.eon.agent.capability.BudgetGuard;
import cn.kong.eon.agent.capability.GateKeeperCapability;
import cn.kong.eon.agent.capability.LoopGuard;
import cn.kong.eon.agent.context.ContextBuilder;
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
 * EonAgent — 单入口统一引擎。
 *
 * <h3>架构设计</h3>
 * 不区分聊天模式和 Agent 模式，采用"单入口 + 能力插拔 + 统一上下文"架构：
 * <ul>
 *   <li>Core Loop：路由 → 组装 → beforeModelCall → build → 调用 → 解析 → 返回/扩展</li>
 *   <li>Extension Loop：门禁 → 执行 → afterToolExecution → 回填 → 回到 Core Loop</li>
 * </ul>
 *
 * <h3>关键设计</h3>
 * <ol>
 *   <li>System Prompt 完全冻结（basePrompt），不拼接动态内容，保证 KV Cache 前缀稳定</li>
 *   <li>tool_catalog 作为独立消息注入（放在 transcript 之后），不破坏 System Prompt 缓存</li>
 *   <li>能力模块按 priority 排序执行（HIGH → NORMAL → LOW）</li>
 *   <li>beforeModelCall 在 ctx.build() 之前调用，能力模块可修改 ContextBuilder</li>
 *   <li>配对修复仅在 Summarize 触发时执行（Snip/Prune 不破坏配对）</li>
 * </ol>
 *
 * <h3>Profile 路由</h3>
 * <ul>
 *   <li>LIGHT_CHAT：不注入工具 Schema（纯聊天）</li>
 *   <li>ASSISTED：只注入网络搜索相关工具（web_search + web_read + finish）</li>
 *   <li>TASK_MULTI：全量注入（LLM 调 todo_write 后自动升级）</li>
 * </ul>
 */
public class EonAgent {
    private static final Logger log = LoggerFactory.getLogger(EonAgent.class);

    private final AgentConfig config;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ToolResultRenderer resultRenderer;
    private final JsonlStore jsonlStore;
    private final String basePrompt;
    private final PolicyRouter policyRouter;
    private final List<CapabilityModule> modules;
    private final GateKeeperCapability gateKeeper;
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
        this.modules = new ArrayList<>();
        this.gateKeeper = null;
        this.toolContext = toolContext;
    }

    public void addCapability(CapabilityModule module) {
        modules.add(module);
        log.debug("Capability module added: {}", module.name());
    }

    public int getCapabilityCount() {
        return modules.size();
    }

    /**
     * 获取按优先级排序的能力模块列表（HIGH→NORMAL→LOW）。
     * 每次调用都创建新列表，避免修改原列表。
     */
    private List<CapabilityModule> getSortedModules() {
        List<CapabilityModule> sorted = new ArrayList<>(modules);
        sorted.sort(Comparator.comparingInt(CapabilityModule::priority));
        return sorted;
    }

    /**
     * 运行 Agent。
     */
    public String run(SessionState state) {
        log.info("=== EonAgent 启动 === session={}", state.getSessionId());
        log.info("用户请求: {}", state.getUserOriginalInput());

        jsonlStore.append(UserMessage.from(state.getUserOriginalInput()));

        while (state.getTurnCount() < config.getLoop().maxSteps) {
            state.incrementTurn();

            // 路由 Profile
            RequestProfile profile = policyRouter.route(state);
            log.info("--- Turn {} [{}] ---", state.getTurnCount(), profile);

            try {
                // ===== Core Loop =====

                // 1. 组装上下文
                ContextBuilder ctx = buildContext(state, profile);

                // 2. beforeModelCall：能力模块前置处理（按优先级排序：HIGH→NORMAL→LOW）
                List<CapabilityModule> sortedModules = getSortedModules();
                for (CapabilityModule module : sortedModules) {
                    if (module.isActive(state)) {
                        module.beforeModelCall(state, ctx);
                    }
                }

                // 3. 构建最终 messages
                List<ChatMessage> messages = ctx.build();
                state.setCurrentMessages(messages);

                // 4. 获取工具 Schema（按 Profile）
                List<ToolSpecification> tools = getToolsForProfile(profile);

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
                    // 无 tool_calls：聊天结束
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

                // 7. afterModelCall：能力模块后置处理（死循环检测等）
                for (CapabilityModule module : modules) {
                    if (module.isActive(state)) {
                        module.afterModelCall(state, response);
                    }
                }

                // ===== Extension Loop =====

                // 8. 门禁校验
                GateKeeperCapability gate = findGateKeeper();
                if (gate != null) {
                    String rejectReason = gate.check(requests, state);
                    if (rejectReason != null) {
                        log.warn("Gate rejected: {}", rejectReason);
                        return "门禁拒绝: " + rejectReason;
                    }
                }

                // 9. 执行工具
                List<ToolExecutionResult> results = executeTools(state);

                // 10. afterToolExecution：能力模块工具后处理（失败计数、Todo 激活等）
                for (int i = 0; i < requests.size(); i++) {
                    ToolExecutionRequest req = requests.get(i);
                    ToolExecutionResult result = results.get(i);
                    boolean success = !result.content().startsWith("[ERROR]");
                    for (CapabilityModule module : modules) {
                        module.afterToolExecution(state, req.name(), success);
                    }
                }

                // 11. 回填
                finalizeAndAppend(state);

                // finish 检测
                if (state.isFinished()) {
                    return state.getLastAssistantText();
                }

            } catch (BudgetGuard.HardBudgetExceededException e) {
                log.error("Budget hard exceeded: {}", e.getMessage());
                return "预算超限（任务终止）: " + e.getMessage();
            } catch (LoopGuard.LoopDetectedException e) {
                log.error("Loop detected: {}", e.getMessage());
                return "检测到死循环: " + e.getMessage();
            }
        }

        log.warn("达到最大步数 {}，强制终止", config.getLoop().maxSteps);
        return "达到最大步数限制";
    }

    private GateKeeperCapability findGateKeeper() {
        for (CapabilityModule m : modules) {
            if (m instanceof GateKeeperCapability g) return g;
        }
        return null;
    }

    /**
     * 组装上下文。
     */
    private ContextBuilder buildContext(SessionState state, RequestProfile profile) {
        ContextBuilder ctx = new ContextBuilder();
        ctx.setSystemPrompt(buildSystemPrompt(state, profile));

        // 摘要（压缩后才有）
        if (state.getCompressionState().getLastSummary() != null) {
            ctx.setSummary(state.getCompressionState().getLastSummary());
        }

        // 历史消息
        List<ChatMessage> transcript = jsonlStore.readAll();
        ctx.setTranscript(transcript);

        // 工具目录（ASSISTED/TASK_MULTI 才注入，独立消息不破坏 System Prompt 缓存）
        if (profile != RequestProfile.LIGHT_CHAT) {
            ctx.setToolCatalog(toolRegistry.getCatalogSummary());
        }

        return ctx;
    }

    /**
     * 构建系统提示词。
     *
     * System Prompt 完全冻结（只含 basePrompt），不拼接任何动态内容。
     * 这样保证 KV Cache 前缀稳定，每轮调用都能命中缓存。
     *
     * policy 已删除（无需告知 LLM 当前 Profile）。
     * tool_catalog 通过 ContextBuilder 的独立消息注入（放在 transcript 之后）。
     */
    private String buildSystemPrompt(SessionState state, RequestProfile profile) {
        return basePrompt;
    }

    /**
     * 按 Profile 获取工具 Schema。
     *
     * LIGHT_CHAT：不注入任何工具 Schema（纯聊天）
     * ASSISTED：只注入网络搜索相关工具（web_search + web_read + finish）
     * TASK_MULTI：全量注入（含 Todo 三件套 + download + MCP）
     */
    private List<ToolSpecification> getToolsForProfile(RequestProfile profile) {
        if (profile == RequestProfile.LIGHT_CHAT) {
            return List.of();
        }
        if (profile == RequestProfile.ASSISTED) {
            // ASSISTED：只注入网络搜索相关工具
            return toolRegistry.getSpecificationsByName(Set.of("web_search", "web_read", "finish"));
        }
        // TASK_MULTI：全量注入
        return toolRegistry.getSpecifications();
    }

    /**
     * 执行工具。
     */
    private List<ToolExecutionResult> executeTools(SessionState state) {
        List<ToolExecutionRequest> requests = state.getPendingToolCalls();
        List<ToolExecutionResult> results = new ArrayList<>();

        for (ToolExecutionRequest req : requests) {
            Map<String, Object> args = parseArgs(req.arguments());
            String reason = (String) args.get("reason");

            String rawResult = toolRegistry.execute(req.name(), args, state, toolContext);
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

    /**
     * 回填到 JSONL。
     */
    private void finalizeAndAppend(SessionState state) {
        AiMessage aiMsg = state.getLastAssistantText() != null && !state.getLastAssistantText().isBlank()
                ? AiMessage.from(state.getLastAssistantText(), state.getPendingToolCalls())
                : AiMessage.from(state.getPendingToolCalls());
        jsonlStore.append(aiMsg);

        for (ToolExecutionResult result : state.getLastToolResults()) {
            jsonlStore.append(ToolExecutionResultMessage.from(
                    result.toolCallId(), result.toolName(), result.content()));
        }

        // 清理临时状态
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
