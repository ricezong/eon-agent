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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <h3>Profile 路由（两档 + 两阶段懒加载）</h3>
 * <ul>
 *   <li>SIMPLE：默认模式，始终注入 tool_catalog（名称+摘要）。
 *       采用两阶段懒加载：
 *       <ol>
 *         <li>第一阶段（pendingToolMounts 为空）：注入 catalog + toolRequestPrompt，
 *             不传工具 Schema。模型从 catalog 中选择需要的工具，在文本输出中声明。</li>
 *         <li>第二阶段（pendingToolMounts 非空）：按声明的工具名挂载完整 Schema，
 *             不传 toolRequestPrompt。模型可以调用工具。</li>
 *       </ol></li>
 *   <li>TASK：LLM 调用过 todo_write 后自动升级，全量挂载工具 Schema，
 *       TodoNavigator 激活。Profile 升级单向，不可降级。</li>
 * </ul>
 */
public class EonAgent {
    private static final Logger log = LoggerFactory.getLogger(EonAgent.class);

    /**
     * 两阶段懒加载：模型声明所需工具的输出格式。
     * 模型在文本输出中以此格式声明需要的工具名，服务端解析后下一轮挂载完整 Schema。
     * 示例：[NEED_TOOLS: web_search, web_read, finish]
     */
    private static final Pattern NEED_TOOLS_PATTERN =
            Pattern.compile("\\[NEED_TOOLS:\\s*(.+?)\\s*\\]", Pattern.CASE_INSENSITIVE);

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

        // 重置懒加载状态（防止多轮对话场景下残留上一轮的 pendingToolMounts）
        state.setPendingToolMounts(null);

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
                    // 无 tool_calls
                    // 两阶段懒加载：检查模型是否在文本中声明了所需工具
                    if (profile == RequestProfile.SIMPLE && state.getPendingToolMounts() == null) {
                        Set<String> declaredTools = parseDeclaredTools(thought);
                        if (declaredTools != null && !declaredTools.isEmpty()) {
                            // 模型声明了所需工具，写入 pendingToolMounts，进入下一轮挂载 Schema
                            Set<String> validTools = filterValidTools(declaredTools);
                            if (!validTools.isEmpty()) {
                                state.setPendingToolMounts(validTools);
                                log.info("Model declared tools: {} -> mounting next turn", validTools);
                                finalizeAndAppend(state);
                                continue;  // 回到 Core Loop，下一轮挂载 Schema
                            }
                        }
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
        ctx.setSystemPrompt(basePrompt);

        // 摘要（压缩后才有）
        if (state.getCompressionState().getLastSummary() != null) {
            ctx.setSummary(state.getCompressionState().getLastSummary());
        }

        // 历史消息
        List<ChatMessage> transcript = jsonlStore.readAll();
        ctx.setTranscript(transcript);

        // 工具目录（始终注入，独立消息不破坏 System Prompt 缓存）
        ctx.setToolCatalog(toolRegistry.getCatalogSummary());

        // 两阶段懒加载：SIMPLE 模式且模型尚未声明工具时，注入 toolRequestPrompt 引导模型选择
        if (profile == RequestProfile.SIMPLE && state.getPendingToolMounts() == null) {
            ctx.setToolRequestPrompt(buildToolRequestPrompt());
        }

        return ctx;
    }

    /**
     * 按 Profile + 两阶段懒加载获取工具 Schema。
     *
     * SIMPLE + pendingToolMounts 为空（第一阶段）：不传工具 Schema，模型只能看到 catalog
     * SIMPLE + pendingToolMounts 非空（第二阶段）：按声明的工具名挂载完整 Schema
     * TASK：全量注入所有工具 Schema
     */
    private List<ToolSpecification> getToolsForProfile(SessionState state, RequestProfile profile) {
        if (profile == RequestProfile.TASK) {
            // TASK：全量注入
            return toolRegistry.getSpecifications();
        }

        // SIMPLE 模式
        Set<String> mounts = state.getPendingToolMounts();
        if (mounts == null || mounts.isEmpty()) {
            // 第一阶段：不传工具 Schema
            return List.of();
        }

        // 第二阶段：按声明的工具名挂载完整 Schema
        List<ToolSpecification> specs = toolRegistry.getSpecificationsByName(mounts);
        log.info("Mounting tools (lazy load): {} -> {} specs", mounts, specs.size());
        return specs;
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

        List<ToolExecutionResult> toolResults = state.getLastToolResults();
        if (toolResults != null) {
            for (ToolExecutionResult result : toolResults) {
                jsonlStore.append(ToolExecutionResultMessage.from(
                        result.toolCallId(), result.toolName(), result.content()));
            }
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

    /**
     * 构建工具请求提示词。
     * 引导模型从 tool_catalog 中选择需要的工具，以 [NEED_TOOLS: tool1, tool2] 格式声明。
     */
    private String buildToolRequestPrompt() {
        return """
                上方是可用工具目录（tool_catalog）。如果你需要使用其中的工具来完成任务，
                请在回复中声明你需要的工具名，格式为：[NEED_TOOLS: tool1, tool2, ...]
                声明后，下一轮你将获得这些工具的完整调用参数。
                如果任务不需要工具（如纯聊天、知识问答），直接回答即可，无需声明。
                注意：你也可以使用 todo_write 建立任务清单来进入任务模式（全量工具可见）。
                """;
    }

    /**
     * 从模型文本输出中解析声明的工具名。
     * 格式：[NEED_TOOLS: web_search, web_read, finish]
     *
     * @return 声明的工具名集合，未找到则返回 null
     */
    private Set<String> parseDeclaredTools(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = NEED_TOOLS_PATTERN.matcher(text);
        if (!matcher.find()) return null;

        String raw = matcher.group(1);
        Set<String> tools = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                tools.add(name);
            }
        }
        return tools.isEmpty() ? null : tools;
    }

    /**
     * 过滤出真实存在的工具名（本地 + MCP）。
     */
    private Set<String> filterValidTools(Set<String> declaredTools) {
        Set<String> valid = new LinkedHashSet<>();
        for (String name : declaredTools) {
            if (toolRegistry.contains(name)) {
                valid.add(name);
            } else {
                log.warn("Declared tool not found: {}", name);
            }
        }
        return valid;
    }
}
