package cn.kong.eon.engine.exec;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.event.AgentToolResult;
import cn.kong.eon.event.AgentToolUse;
import cn.kong.eon.runtime.RunContext;
import cn.kong.eon.tool.ToolRuntime;
import cn.kong.eon.tool.ToolOutcome;
import cn.kong.eon.tool.ToolService;
import cn.kong.eon.tool.model.ToolCallRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * 工具执行处理器。封装工具执行全流程：参数解析 → 执行 → 事件发射。
 * 支持并行执行，串行豁免清单（todo_write/AskQuestion）强制串行。
 * <p>
 * 无状态：会话级依赖（熔断器、各 store）全部从 {@code r.session()} 取，
 * {@link ToolRuntime} 在每次调用时现场组装，不再跨会话持有。
 * 因此可作为应用级单例，线程池也随之收敛为一个（原先每会话一个）。
 */
@Component
public class ToolCallDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ToolCallDispatcher.class);

    /** 顺序敏感或交互互斥的工具强制串行。 */
    private static final Set<String> SERIAL_ONLY = Set.of("todo_write", "AskQuestion");

    private final ToolService toolService;
    private final ExecutorService parallelExecutor;
    private final ObjectMapper objectMapper;

    public ToolCallDispatcher(ToolService toolService, AgentConfig config, ObjectMapper objectMapper) {
        this.toolService = toolService;
        this.objectMapper = objectMapper;
        int parallelism = config.getTools().getParallelism();
        this.parallelExecutor = Executors.newFixedThreadPool(Math.max(1, parallelism), r -> {
            Thread t = new Thread(r, "tool-exec");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 执行本轮待执行的工具调用，保持与请求列表一致的顺序。
     */
    public List<ToolCallRecord> execute(RunContext r) {
        List<ToolExecutionRequest> requests = r.turn().pendingToolCalls();
        ToolRuntime runtime = new ToolRuntime(
                r.session().todoStore(),
                r.session().artifactStore(),
                r.session().memoryStore(),
                r.session().pathResolver(),
                r.task().turnCount(),
                r.session().sessionId());

        ToolCallRecord[] results = new ToolCallRecord[requests.size()];
        List<Future<ToolCallRecord>> futures = new ArrayList<>();
        List<Integer> pendingIndices = new ArrayList<>();

        for (int i = 0; i < requests.size(); i++) {
            ToolExecutionRequest req = requests.get(i);
            if (SERIAL_ONLY.contains(req.name())) {
                results[i] = runSerial(r, runtime, req);
            } else {
                final ToolExecutionRequest call = req;
                futures.add(parallelExecutor.submit(() -> executeSingle(r, runtime, call)));
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
                results[idx] = syntheticError(r, req, "并行执行被中断: " + e.getMessage());
            } catch (ExecutionException e) {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                log.error("[ToolExecution] 并行执行失败 {}: {}", req.name(), msg, e);
                results[idx] = syntheticError(r, req, "工具执行异常: " + msg);
            }
        }

        List<ToolCallRecord> resultList = List.of(results);
        r.turn().setToolResults(resultList);
        return resultList;
    }

    // ═══════════════════ 单次执行 ═══════════════════

    private ToolCallRecord runSerial(RunContext r, ToolRuntime runtime, ToolExecutionRequest req) {
        try {
            return executeSingle(r, runtime, req);
        } catch (Exception e) {
            return syntheticError(r, req, "工具执行异常: " + e.getMessage());
        }
    }

    /**
     * 执行单个工具请求：发出 tool_use 事件 → 执行 → 发出 tool_result 事件。
     */
    private ToolCallRecord executeSingle(RunContext r, ToolRuntime runtime, ToolExecutionRequest req) {
        String turnId = r.task().turnId();

        // 发出 engine.tool_use 事件
        r.emit(AgentToolUse.now(turnId, req.id(), req.name(), req.arguments()));

        // 熔断拦截
        if (r.session().circuitBreaker().isTripped(req.name())) {
            String msg = r.session().circuitBreaker().trippedMessage(req.name());
            log.warn("[ToolExecution] 工具 '{}' 已熔断，跳过执行", req.name());
            return syntheticError(r, req, msg);
        }

        Map<String, Object> args = parseArgs(req.arguments());
        ToolOutcome outcome = toolService.execute(req.name(), args, runtime);

        // 发出 engine.tool_result 事件
        r.emit(AgentToolResult.now(turnId, req.id(), req.name(),
                outcome.content(), outcome.toolResultView(), outcome.success()));

        return new ToolCallRecord(req.id(), req.name(), outcome.success(), outcome.content(), outcome.toolResultView());
    }

    /** 合成错误结果（用于异常隔离）。 */
    private ToolCallRecord syntheticError(RunContext r, ToolExecutionRequest req, String errorMsg) {
        String turnId = r.task().turnId();
        ToolOutcome outcome = ToolOutcome.failure(errorMsg);

        r.emit(AgentToolResult.now(turnId, req.id(), req.name(),
                outcome.content(), outcome.toolResultView(), false));

        return new ToolCallRecord(req.id(), req.name(), outcome.success(), outcome.content(), outcome.toolResultView());
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

    @PreDestroy
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
