package cn.kong.eon.agent;

import cn.kong.eon.agent.capability.CapabilityModule;
import cn.kong.eon.agent.capability.CapabilityResult;
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
 * Agent 单入口统一引擎。
 *
 * Core Loop: 路由 → 组装上下文 → beforeModelCall → build → 调用 LLM → 解析 → 返回/扩展
 * Extension Loop: beforeToolExecution → 执行工具 → afterToolExecution → 回填 → 回到 Core Loop
 *
 * System Prompt 完全冻结（basePrompt），tool_catalog 作为独立消息注入，保证 KV Cache 前缀稳定。
 * Profile 两档：SIMPLE（默认，两阶段懒加载工具 Schema）、TASK（todo_write 后升级，全量挂载）。
 */
public class EonAgent {
    private static final Logger log = LoggerFactory.getLogger(EonAgent.class);

    /** 两阶段懒加载：模型声明所需工具的格式。 */
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
        this.toolContext = toolContext;
    }

    public void addCapability(CapabilityModule module) {
        modules.add(module);
        log.debug("Capability module added: {}", module.name());
    }

    public int getCapabilityCount() {
        return modules.size();
    }

    /** 按 Layer + orderInLayer 排序，每次调用创建新列表。 */
    private List<CapabilityModule> getSortedModules() {
        List<CapabilityModule> sorted = new ArrayList<>(modules);
        sorted.sort(Comparator
                .comparingInt((CapabilityModule m) -> m.layer().order)
                .thenComparingInt(CapabilityModule::orderInLayer));
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

                // 2. beforeModelCall
                List<CapabilityModule> sortedModules = getSortedModules();
                for (CapabilityModule module : sortedModules) {
                    if (!module.isActive(state)) continue;
                    CapabilityResult r = module.beforeModelCall(state, ctx);
                    if (r.isAbort()) {
                        return formatAbort(r);
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
                    // 无 tool_calls：检查两阶段懒加载声明
                    if (profile == RequestProfile.SIMPLE && state.getPendingToolMounts() == null) {
                        Set<String> declaredTools = parseDeclaredTools(thought);
                        if (declaredTools != null && !declaredTools.isEmpty()) {
                            Set<String> validTools = filterValidTools(declaredTools);
                            if (!validTools.isEmpty()) {
                                state.setPendingToolMounts(validTools);
                                log.info("Model declared tools: {} -> mounting next turn", validTools);
                                finalizeAndAppend(state);
                                continue;
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

                // 7. afterModelCall
                for (CapabilityModule module : sortedModules) {
                    if (!module.isActive(state)) continue;
                    CapabilityResult r = module.afterModelCall(state, response);
                    if (r.isAbort()) {
                        return formatAbort(r);
                    }
                }

                // ===== Extension Loop =====

                // 8. beforeToolExecution
                for (CapabilityModule module : sortedModules) {
                    if (!module.isActive(state)) continue;
                    CapabilityResult r = module.beforeToolExecution(state, requests);
                    if (r.isAbort()) {
                        return formatAbort(r);
                    }
                }

                // 9. 执行工具
                List<ToolExecutionResult> results = executeTools(state);

                // 10. afterToolExecution
                for (int i = 0; i < requests.size(); i++) {
                    ToolExecutionRequest req = requests.get(i);
                    ToolExecutionResult result = results.get(i);
                    boolean success = !result.content().startsWith("[ERROR]");
                    for (CapabilityModule module : sortedModules) {
                        if (!module.isActive(state)) continue;
                        CapabilityResult r = module.afterToolExecution(state, req.name(), success);
                        if (r.isAbort()) {
                            return formatAbort(r);
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

    /** 格式化能力模块的中断返回值。 */
    private String formatAbort(CapabilityResult r) {
        String category = r.getCategory();
        String reason = r.getReason();
        log.warn("Capability aborted: category={}, reason={}", category, reason);
        if (category == null) {
            return "能力中断: " + reason;
        }
        return switch (category) {
            case "BUDGET_EXCEEDED" -> "预算超限（任务终止）: " + reason;
            case "LOOP_DETECTED"   -> "检测到死循环: " + reason;
            case "GATE_REJECTED"   -> "门禁拒绝: " + reason;
            default                 -> "能力中断[" + category + "]: " + reason;
        };
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

        // SIMPLE 第一阶段：注入 toolRequestPrompt 引导模型选择工具
        if (profile == RequestProfile.SIMPLE && state.getPendingToolMounts() == null) {
            ctx.setToolRequestPrompt(buildToolRequestPrompt());
        }

        return ctx;
    }

    /**
     * 按 Profile + 两阶段懒加载获取工具 Schema。
     * SIMPLE + 未声明：不传 Schema
     * SIMPLE + 已声明：按声明的工具名挂载
     * TASK：全量注入
     */
    private List<ToolSpecification> getToolsForProfile(SessionState state, RequestProfile profile) {
        if (profile == RequestProfile.TASK) {
            return toolRegistry.getSpecifications();
        }

        Set<String> mounts = state.getPendingToolMounts();
        if (mounts == null || mounts.isEmpty()) {
            return List.of();
        }

        List<ToolSpecification> specs = toolRegistry.getSpecificationsByName(mounts);
        log.info("Mounting tools (lazy load): {} -> {} specs", mounts, specs.size());
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

    /** 引导模型从 catalog 中选择需要的工具。 */
    private String buildToolRequestPrompt() {
        return """
                上方是可用工具目录（tool_catalog）。如果你需要使用其中的工具来完成任务，
                请在回复中声明你需要的工具名，格式为：[NEED_TOOLS: tool1, tool2, ...]
                声明后，下一轮你将获得这些工具的完整调用参数。
                如果任务不需要工具（如纯聊天、知识问答），直接回答即可，无需声明。
                注意：你也可以使用 todo_write 建立任务清单来进入任务模式（全量工具可见）。
                """;
    }

    /** 从模型文本输出中解析 [NEED_TOOLS: tool1, tool2] 格式的工具声明。 */
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

    /** 过滤出真实存在的工具名（本地 + MCP）。 */
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
