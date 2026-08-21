package cn.kong.eon.agent.support;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolExecutionResult;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolRegistry;
import cn.kong.eon.tool.ToolResultRenderer;
import cn.kong.eon.util.JsonMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 工具执行处理器。封装工具执行全流程：
 *   参数解析 → 执行 → enable_tools 拦截 → finish 拦截 → 结果渲染 → 日志
 * 从 EonAgent 抽取，让主循环不再关注工具执行细节。
 */
public class ToolExecutionHandler {
    private static final Logger log = LoggerFactory.getLogger(ToolExecutionHandler.class);

    private static final String ENABLE_TOOLS = "enable_tools";
    private static final String FINISH = "finish";

    private final ToolRegistry toolRegistry;
    private final ToolResultRenderer resultRenderer;
    private final ToolContext toolContext;
    private final TurnLogger logger;

    public ToolExecutionHandler(ToolRegistry toolRegistry,
                                ToolResultRenderer resultRenderer,
                                ToolContext toolContext,
                                TurnLogger logger) {
        this.toolRegistry = toolRegistry;
        this.resultRenderer = resultRenderer;
        this.toolContext = toolContext;
        this.logger = logger;
    }

    /**
     * 执行所有待执行的工具调用。
     * 包含 enable_tools 拦截（设置 pendingToolMounts）和 finish 拦截（设置 finished）。
     * 工具执行日志写入 rec，由 TurnLogger 统一 flush。
     */
    public List<ToolExecutionResult> execute(TurnRecord rec, SessionState state) {
        List<ToolExecutionRequest> requests = state.getPendingToolCalls();
        List<ToolExecutionResult> results = new ArrayList<>();

        for (ToolExecutionRequest req : requests) {
            Map<String, Object> args = parseArgs(req.arguments());
            String reason = (String) args.get("reason");

            String rawResult = toolRegistry.execute(req.name(), args, state, toolContext);

            // 拦截 enable_tools：从参数中提取工具名，设置 pendingToolMounts
            if (ENABLE_TOOLS.equals(req.name())) {
                interceptEnableTools(args, state);
            }

            String rendered = resultRenderer.render(req.name(), req.id(), reason, rawResult, state);
            boolean success = !rawResult.startsWith("[ERROR]");
            logger.toolExecuted(rec, req.name(), success, logger.summarizeArgs(req.name(), args), rendered.length());

            results.add(ToolExecutionResult.of(req.id(), req.name(), rendered));

            // finish 拦截：设置 finished 后立即停止后续工具执行
            if (FINISH.equals(req.name()) && state.isFinished()) {
                break;
            }
        }

        state.setLastToolResults(results);
        return results;
    }

    /** 从 enable_tools 参数中提取有效工具名，设置 pendingToolMounts。 */
    @SuppressWarnings("unchecked")
    private void interceptEnableTools(Map<String, Object> args, SessionState state) {
        Object toolsRaw = args.get("tools");
        if (!(toolsRaw instanceof List<?> toolList) || toolList.isEmpty()) return;

        Set<String> declaredTools = new LinkedHashSet<>();
        for (Object item : toolList) {
            String name = String.valueOf(item).trim();
            if (toolRegistry.contains(name)) {
                declaredTools.add(name);
            } else {
                log.warn("[Mount] declared tool not found: {}", name);
            }
        }
        if (!declaredTools.isEmpty()) {
            state.setPendingToolMounts(declaredTools);
            log.info("[Mount] declared: {} -> {} valid tools", toolList, declaredTools);
        }
    }

    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JsonMapper.get().readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[Tool] failed to parse arguments: {}", json, e);
            return Map.of();
        }
    }
}
