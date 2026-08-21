package cn.kong.eon.agent.support;

import cn.kong.eon.loop.LoopDetector;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolExecutionResult;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolOutcome;
import cn.kong.eon.tool.ToolRegistry;
import cn.kong.eon.tool.ToolResultRenderer;
import cn.kong.eon.util.JsonMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具执行处理器。封装工具执行全流程：
 * 参数解析 → 执行 → finish 拦截 → todo_write 后处理 → 结果渲染 → 日志。
 */
public class ToolExecutionHandler {
    private static final Logger log = LoggerFactory.getLogger(ToolExecutionHandler.class);

    private static final String FINISH = "finish";
    private static final String TODO_WRITE = "todo_write";

    private final ToolRegistry toolRegistry;
    private final ToolResultRenderer resultRenderer;
    private final ToolContext toolContext;
    private final TurnLogger logger;
    private final LoopDetector loopDetector;

    public ToolExecutionHandler(ToolRegistry toolRegistry,
                                ToolResultRenderer resultRenderer,
                                ToolContext toolContext,
                                TurnLogger logger,
                                LoopDetector loopDetector) {
        this.toolRegistry = toolRegistry;
        this.resultRenderer = resultRenderer;
        this.toolContext = toolContext;
        this.logger = logger;
        this.loopDetector = loopDetector;
    }

    /**
     * 执行所有待执行的工具调用。包含 finish 拦截和 todo_write 后处理。
     */
    public List<ToolExecutionResult> execute(TurnRecord rec, SessionState state) {
        List<ToolExecutionRequest> requests = state.getPendingToolCalls();
        List<ToolExecutionResult> results = new ArrayList<>();

        for (ToolExecutionRequest req : requests) {
            Map<String, Object> args = parseArgs(req.arguments());

            ToolOutcome outcome = toolRegistry.execute(req.name(), args, state, toolContext);

            String rendered = resultRenderer.render(req.name(), outcome, state);
            String argsSummary = toolRegistry.get(req.name()) != null
                    ? toolRegistry.get(req.name()).summarizeArgs(args)
                    : truncate(args.toString(), 80);
            logger.toolExecuted(rec, req.name(), outcome.success(), argsSummary, rendered.length());

            results.add(ToolExecutionResult.of(req.id(), req.name(), outcome, rendered));

            // finish 拦截：设置 finished 后立即停止后续工具执行
            if (FINISH.equals(req.name()) && state.isFinished()) {
                break;
            }

            // todo_write 后处理：标记 todoBeenUsed + 记录快照用于无进展检测
            if (TODO_WRITE.equals(req.name()) && outcome.success()) {
                if (!state.hasTodoBeenUsed()) {
                    state.setTodoBeenUsed(true);
                    log.info("TodoNavigator activated: todo_write called");
                }
                var snapResult = loopDetector.recordTodoSnapshot(toolContext.todoStore().getAll().toString());
                if (snapResult.shouldWarn()) {
                    state.addNudge(snapResult.message());
                }
            }
        }

        state.setLastToolResults(results);
        return results;
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

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
