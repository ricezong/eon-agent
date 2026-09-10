package cn.kong.eon.agent.exec;

import cn.kong.eon.agent.event.TurnEvent;
import cn.kong.eon.agent.event.AgentToolResult;
import cn.kong.eon.agent.event.AgentToolUse;
import cn.kong.eon.session.SessionState;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolOutcome;
import cn.kong.eon.tool.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 工具执行处理器。封装工具执行全流程：参数解析 → 执行 → 事件发射。
 * 支持并行执行，串行豁免清单（todo_write/AskQuestion）强制串行。
 */
public class ToolExecHandler {
    private static final Logger log = LoggerFactory.getLogger(ToolExecHandler.class);

    private static final int ARGS_SUMMARY_LIMIT = 80;

    /** 串行豁免清单：顺序敏感或交互互斥的工具强制串行。 */
    private static final Set<String> SERIAL_ONLY = Set.of("todo_write", "AskQuestion");

    private final ToolRegistry toolRegistry;
    private final ToolContext toolContext;
    private final ToolHealthTracker tracker;
    private final ExecutorService parallelExecutor;
    private final Consumer<TurnEvent> emitter;

    private final ObjectMapper objectMapper;

    /** 带 ObjectMapper 注入的构造函数。 */
    public ToolExecHandler(ToolRegistry toolRegistry,
                           ToolContext toolContext,
                           Consumer<TurnEvent> emitter,
                           ToolHealthTracker tracker,
                           int parallelism,
                           ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.toolContext = toolContext;
        this.emitter = emitter;
        this.tracker = tracker;
        this.objectMapper = objectMapper;
        this.parallelExecutor = Executors.newFixedThreadPool(Math.max(1, parallelism), r -> {
            Thread t = new Thread(r, "tool-exec");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 执行所有待执行的工具调用，保持与请求列表一致的顺序。
     */
    public List<ToolExecResult> execute(SessionState state) {
        List<ToolExecutionRequest> requests = state.getPendingToolCalls();
        ToolExecResult[] results = new ToolExecResult[requests.size()];
        List<Future<ToolExecResult>> futures = new ArrayList<>();
        List<Integer> pendingIndices = new ArrayList<>();

        for (int i = 0; i < requests.size(); i++) {
            ToolExecutionRequest req = requests.get(i);
            if (SERIAL_ONLY.contains(req.name())) {
                results[i] = runSerial(req, state);
            } else {
                final ToolExecutionRequest r = req;
                futures.add(parallelExecutor.submit(() -> executeSingle(r, state)));
                pendingIndices.add(i);
            }
        }

        for (int j = 0; j < pendingIndices.size(); j++) {
            int idx = pendingIndices.get(j);
            ToolExecutionRequest req = requests.get(idx);
            try {
                results[idx] = futures.get(j).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results[idx] = syntheticError(req, "并行执行被中断: " + e.getMessage(), state);
            } catch (ExecutionException e) {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                log.error("[ToolExecution] 并行执行失败 {}: {}", req.name(), msg, e);
                results[idx] = syntheticError(req, "工具执行异常: " + msg, state);
            }
        }

        List<ToolExecResult> resultList = List.of(results);
        state.setLastToolResults(resultList);
        return resultList;
    }

    // ═══════════════════ 单次执行 ═══════════════════

    private ToolExecResult runSerial(ToolExecutionRequest req, SessionState state) {
        try {
            return executeSingle(req, state);
        } catch (Exception e) {
            return syntheticError(req, "工具执行异常: " + e.getMessage(), state);
        }
    }

    /**
     * 执行单个工具请求：发出 tool_use 事件 → 执行 → 发出 tool_result 事件。
     */
    private ToolExecResult executeSingle(ToolExecutionRequest req, SessionState state) {
        String turnId = state.getTurnId();

        // 发出 agent.tool_use 事件
        String perm = toolRegistry.getPermission(req.name()) != null
                ? toolRegistry.getPermission(req.name()).name() : "UNKNOWN";
        emit(AgentToolUse.now(turnId, req.id(), req.name(), req.arguments(), perm));

        // 熔断拦截
        if (tracker.isTripped(req.name())) {
            String msg = tracker.trippedMessage(req.name());
            log.warn("[ToolExecution] 工具 '{}' 已熔断，跳过执行", req.name());
            return syntheticError(req, msg, state);
        }

        Map<String, Object> args = parseArgs(req.arguments());
        ToolOutcome outcome = toolRegistry.execute(req.name(), args, state, toolContext);

        // 发出 agent.tool_result 事件
        emit(AgentToolResult.now(turnId, req.id(), req.name(),
                outcome.content(), outcome.structuredContent(), outcome.success()));

        return ToolExecResult.of(req.id(), req.name(), outcome, outcome.content());
    }

    /** 合成错误结果（用于异常隔离）。 */
    private ToolExecResult syntheticError(ToolExecutionRequest req, String errorMsg, SessionState state) {
        String turnId = state.getTurnId();
        ToolOutcome outcome = ToolOutcome.failure(errorMsg);

        emit(AgentToolResult.now(turnId, req.id(), req.name(),
                outcome.content(), outcome.structuredContent(), false));

        return ToolExecResult.of(req.id(), req.name(), outcome, outcome.content());
    }

    // ═══════════════════ 工具方法 ═══════════════════

    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[ToolExecution] 参数解析失败: {}", json, e);
            return Map.of();
        }
    }

    private void emit(TurnEvent event) {
        if (emitter != null) {
            emitter.accept(event);
        }
    }

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
