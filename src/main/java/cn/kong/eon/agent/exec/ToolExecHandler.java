package cn.kong.eon.agent.exec;

import cn.kong.eon.agent.turn.TurnLogger;
import cn.kong.eon.agent.turn.TurnRecord;
import cn.kong.eon.config.ObjectMapperConfig;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolExecResult;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolOutcome;
import cn.kong.eon.tool.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 工具执行处理器。封装工具执行全流程：参数解析 → 执行 → 日志。
 * 支持并行执行，串行豁免清单（todo_write/AskQuestion）强制串行。
 * 工具结果以原始输出回填，由入站管线统一决定落盘与格式化策略。
 */
public class ToolExecHandler {
    private static final Logger log = LoggerFactory.getLogger(ToolExecHandler.class);

    private static final int ARGS_SUMMARY_LIMIT = 80;

    /** 串行豁免清单：顺序敏感或交互互斥的工具强制串行。 */
    private static final Set<String> SERIAL_ONLY = Set.of("todo_write", "AskQuestion");

    private final ToolRegistry toolRegistry;
    private final ToolContext toolContext;
    private final TurnLogger logger;
    private final ToolHealthTracker tracker;
    private final ExecutorService parallelExecutor;

    public ToolExecHandler(ToolRegistry toolRegistry,
                           ToolContext toolContext,
                           TurnLogger logger,
                           ToolHealthTracker tracker,
                           int parallelism) {
        this.toolRegistry = toolRegistry;
        this.toolContext = toolContext;
        this.logger = logger;
        this.tracker = tracker;
        this.parallelExecutor = Executors.newFixedThreadPool(Math.max(1, parallelism), r -> {
            Thread t = new Thread(r, "tool-exec");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 执行所有待执行的工具调用，保持与请求列表一致的顺序。
     * 串行豁免工具立即执行，其余提交并行后统一收集。
     */
    public List<ToolExecResult> execute(TurnRecord rec, SessionState state) {
        List<ToolExecutionRequest> requests = state.getPendingToolCalls();
        ToolExecResult[] results = new ToolExecResult[requests.size()];
        List<Future<ToolExecResult>> futures = new ArrayList<>();
        List<Integer> pendingIndices = new ArrayList<>();

        // 第一遍：分发——串行的立即执行，并行的提交线程池
        for (int i = 0; i < requests.size(); i++) {
            ToolExecutionRequest req = requests.get(i);
            if (SERIAL_ONLY.contains(req.name())) {
                results[i] = runSerial(req, rec, state);
            } else {
                final ToolExecutionRequest r = req;
                futures.add(parallelExecutor.submit(() -> executeSingle(r, rec, state)));
                pendingIndices.add(i);
            }
        }

        // 第二遍：收集并行结果
        for (int j = 0; j < pendingIndices.size(); j++) {
            int idx = pendingIndices.get(j);
            ToolExecutionRequest req = requests.get(idx);
            try {
                results[idx] = futures.get(j).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results[idx] = syntheticError(req, "并行执行被中断: " + e.getMessage(), rec);
            } catch (ExecutionException e) {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                log.error("[ToolExecution] 并行执行失败 {}: {}", req.name(), msg, e);
                results[idx] = syntheticError(req, "工具执行异常: " + msg, rec);
            }
        }

        List<ToolExecResult> resultList = List.of(results);
        state.setLastToolResults(resultList);
        return resultList;
    }

    // ═══════════════════ 单次执行 ═══════════════════

    /** 串行执行单个工具（含异常兜底）。 */
    private ToolExecResult runSerial(ToolExecutionRequest req, TurnRecord rec, SessionState state) {
        try {
            return executeSingle(req, rec, state);
        } catch (Exception e) {
            return syntheticError(req, "工具执行异常: " + e.getMessage(), rec);
        }
    }

    /**
     * 执行单个工具请求：参数解析 → 执行 → 日志 → 封装结果。
     */
    private ToolExecResult executeSingle(ToolExecutionRequest req, TurnRecord rec, SessionState state) {
        // 熔断拦截：跳过执行，合成错误结果告知 LLM
        if (tracker.isTripped(req.name())) {
            String msg = tracker.trippedMessage(req.name());
            log.warn("[ToolExecution] 工具 '{}' 已熔断，跳过执行", req.name());
            return syntheticError(req, msg, rec);
        }

        Map<String, Object> args = parseArgs(req.arguments());

        ToolOutcome outcome = toolRegistry.execute(req.name(), args, state, toolContext);

        String argsSummary = truncate(args.toString());
        logger.toolExecuted(rec, req.name(), outcome.success(), argsSummary, outcome.content().length());

        return ToolExecResult.of(req.id(), req.name(), outcome, outcome.content());
    }

    /** 合成错误结果（用于异常隔离）。 */
    private ToolExecResult syntheticError(ToolExecutionRequest req, String errorMsg, TurnRecord rec) {
        ToolOutcome outcome = ToolOutcome.failure(errorMsg);
        logger.toolExecuted(rec, req.name(), false, "(错误)", outcome.content().length());
        return ToolExecResult.of(req.id(), req.name(), outcome, outcome.content());
    }

    // ═══════════════════ 工具方法 ═══════════════════

    /** 解析工具参数 JSON 为 Map。 */
    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return ObjectMapperConfig.getObjectMapper().readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[ToolExecution] 参数解析失败: {}", json, e);
            return Map.of();
        }
    }

    private static String truncate(String s) {
        return s.length() > ToolExecHandler.ARGS_SUMMARY_LIMIT ? s.substring(0, ToolExecHandler.ARGS_SUMMARY_LIMIT) + "..." : s;
    }

    /** 关闭线程池。 */
    public void shutdown() {
        parallelExecutor.shutdown();
        try {
            if (!parallelExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                parallelExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            parallelExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
