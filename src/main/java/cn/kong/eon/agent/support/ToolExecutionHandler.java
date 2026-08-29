package cn.kong.eon.agent.support;

import cn.kong.eon.agent.loop.LoopDetector;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolExecutionResult;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolOutcome;
import cn.kong.eon.tool.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * 工具执行处理器。封装工具执行全流程：参数解析 → 执行 → todo_write 后处理 → 日志。
 * 支持并行执行，串行豁免清单（todo_write/AskQuestion）强制串行。
 * <p>
 * 这里<b>不做任何结果渲染与大小控制</b>：工具结果以原始输出回填，
 * 由入站管线统一决定"落不落盘、怎么格式化"。
 * 渲染原本挂在工具层，导致上下文大小策略散落在两个地方、且只对工具结果生效。
 */
public class ToolExecutionHandler {
    private static final Logger log = LoggerFactory.getLogger(ToolExecutionHandler.class);

    private static final String TODO_WRITE = "todo_write";
    /** 串行豁免清单：顺序敏感或交互互斥的工具强制串行 */
    private static final Set<String> SERIAL_ONLY = Set.of(TODO_WRITE, "AskQuestion");

    private final ToolRegistry toolRegistry;
    private final ToolContext toolContext;
    private final TurnLogger logger;
    private final LoopDetector loopDetector;
    private final ObjectMapper objectMapper;
    private final ExecutorService parallelExecutor;

    public ToolExecutionHandler(ToolRegistry toolRegistry,
                                ToolContext toolContext,
                                TurnLogger logger,
                                LoopDetector loopDetector,
                                int parallelism,
                                ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.toolContext = toolContext;
        this.logger = logger;
        this.loopDetector = loopDetector;
        this.objectMapper = objectMapper;
        this.parallelExecutor = Executors.newFixedThreadPool(Math.max(1, parallelism), r -> {
            Thread t = new Thread(r, "tool-exec");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 执行所有待执行的工具调用。被熔断的工具跳过执行，返回合成错误结果。
     */
    public List<ToolExecutionResult> execute(TurnRecord rec, SessionState state) {
        List<ToolExecutionRequest> requests = state.getPendingToolCalls();
        int n = requests.size();

        // 单请求直接执行
        if (n == 1) {
            List<ToolExecutionResult> results = new ArrayList<>(1);
            results.add(executeSingle(requests.get(0), rec, state));
            state.setLastToolResults(results);
            return results;
        }

        // 多请求：分区 — 串行豁免工具 vs 可并行工具
        List<ToolExecutionResult> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) results.add(null);

        List<Integer> parallelIndices = new ArrayList<>();
        List<Integer> serialIndices = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            String toolName = requests.get(i).name();
            if (SERIAL_ONLY.contains(toolName)) {
                serialIndices.add(i);
            } else {
                parallelIndices.add(i);
            }
        }

        // 并行执行非豁免工具
        if (!parallelIndices.isEmpty()) {
            List<Future<ToolExecutionResult>> futures = new ArrayList<>();
            for (int idx : parallelIndices) {
                final int i = idx;
                final ToolExecutionRequest req = requests.get(i);
                futures.add(parallelExecutor.submit(() -> executeSingle(req, rec, state)));
            }
            for (int j = 0; j < parallelIndices.size(); j++) {
                int originalIdx = parallelIndices.get(j);
                try {
                    results.set(originalIdx, futures.get(j).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.set(originalIdx, syntheticError(requests.get(originalIdx),
                            "并行执行被中断: " + e.getMessage(), rec, state));
                } catch (ExecutionException e) {
                    log.error("[工具] 并行执行失败 {}: {}", requests.get(originalIdx).name(), e.getMessage(), e);
                    results.set(originalIdx, syntheticError(requests.get(originalIdx),
                            "工具执行异常: " + e.getCause().getMessage(), rec, state));
                }
            }
        }

        // 串行执行豁免工具
        for (int idx : serialIndices) {
            results.set(idx, executeSingle(requests.get(idx), rec, state));
        }

        // 确保没有 null 残留（防御性）
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) == null) {
                results.set(i, syntheticError(requests.get(i), "内部错误: 结果未生成", rec, state));
            }
        }

        state.setLastToolResults(results);
        return results;
    }

    /**
     * 执行单个工具请求（含熔断检查、参数解析、执行、渲染、日志、todo_write 后处理）。
     */
    private ToolExecutionResult executeSingle(ToolExecutionRequest req, TurnRecord rec, SessionState state) {
        // 被熔断的工具跳过执行
        if (loopDetector.isToolTripped(req.name())) {
            ToolOutcome tripped = ToolOutcome.failure(
                    "工具 " + req.name() + " 已被熔断（连续失败过多），请标记 blocked 或调整计划，不要再调用此工具");
            logger.toolExecuted(rec, req.name(), false, "(已熔断)", tripped.content().length());
            return ToolExecutionResult.of(req.id(), req.name(), tripped, tripped.content());
        }

        Map<String, Object> args = parseArgs(req.arguments());

        ToolOutcome outcome = toolRegistry.execute(req.name(), args, state, toolContext);

        String argsSummary = args.toString().length() > 80 ? args.toString().substring(0, 80) + "..." : args.toString();
        logger.toolExecuted(rec, req.name(), outcome.success(), argsSummary, outcome.content().length());

        // 原始输出直接回填；落盘与格式化由入站管线负责
        ToolExecutionResult result = ToolExecutionResult.of(req.id(), req.name(), outcome, outcome.content());

        // todo_write 后处理
        if (TODO_WRITE.equals(req.name()) && outcome.success()) {
            if (!state.hasTodoBeenUsed()) {
                state.setTodoBeenUsed(true);
                log.info("TodoNavigator 已激活: todo_write 被调用");
            }
            var snapResult = loopDetector.recordTodoSnapshot(toolContext.todoStore().getAll().toString());
            if (snapResult.shouldWarn()) {
                state.addNudge(snapResult.message());
            }
        }

        return result;
    }

    /**
     * 合成错误结果（用于并行异常隔离）。
     */
    private ToolExecutionResult syntheticError(ToolExecutionRequest req, String errorMsg,
                                               TurnRecord rec, SessionState state) {
        ToolOutcome outcome = ToolOutcome.failure(errorMsg);
        logger.toolExecuted(rec, req.name(), false, "(错误)", outcome.content().length());
        return ToolExecutionResult.of(req.id(), req.name(), outcome, outcome.content());
    }

    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("[工具] 参数解析失败: {}", json, e);
            return Map.of();
        }
    }

    /**
     * 关闭线程池。
     */
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
