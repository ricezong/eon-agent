package cn.kong.eon.agent.support;

import cn.kong.eon.agent.loop.LoopDetector;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolExecutionResult;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionHandlerTest {

    private ToolExecutionHandler handler;
    private ToolRegistry registry;
    private LoopDetector loopDetector;
    private TurnLogger logger;
    private ToolResultRenderer renderer;
    private ToolContext toolContext;
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(java.util.Set.of(), mapper);
        loopDetector = new LoopDetector(3, 5, 6, 3, 5);
        logger = new TurnLogger(new cn.kong.eon.config.AgentConfig());
        renderer = new ToolResultRenderer(new cn.kong.eon.store.ArtifactStore(tempDir));
        toolContext = new ToolContext(
                new cn.kong.eon.store.TodoStore(),
                new cn.kong.eon.store.ArtifactStore(tempDir),
                new cn.kong.eon.store.MemoryStore(tempDir, mapper),
                null, null, null, tempDir.toString());
        handler = new ToolExecutionHandler(registry, renderer, toolContext, logger, loopDetector, 4, mapper);
    }

    @AfterEach
    void tearDown() {
        handler.shutdown();
    }

    private ToolDescriptor simpleTool(String name, java.util.function.Function<Map<String, Object>, String> executorFn) {
        return new ToolDescriptor(
                name, "test tool: " + name, ToolPermission.READONLY,
                ToolSpecification.builder().name(name).description("test").build(),
                (args, state, ctx) -> {
                    String result = executorFn.apply(args);
                    if (result.startsWith("FAIL:")) {
                        return ToolOutcome.failure(result.substring(5));
                    }
                    return ToolOutcome.success(result);
                }
        );
    }

    private SessionState createStateWithPending(List<ToolExecutionRequest> requests) {
        SessionState state = SessionState.create("s1", "test");
        state.setPendingToolCalls(requests);
        return state;
    }

    @Test
    void execute_singleRequest_returnsOneResult() {
        registry.register(simpleTool("read_file", args -> "file content"));
        SessionState state = createStateWithPending(List.of(
                ToolExecutionRequest.builder().id("c1").name("read_file").arguments("{}").build()
        ));
        TurnRecord rec = logger.newRecord();

        List<ToolExecutionResult> results = handler.execute(rec, state);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(0).content()).contains("file content");
        assertThat(state.getLastToolResults()).hasSize(1);
    }

    @Test
    void execute_multipleParallelRequests_preservesOrder() {
        registry.register(simpleTool("tool_a", args -> "result_a"));
        registry.register(simpleTool("tool_b", args -> "result_b"));
        SessionState state = createStateWithPending(List.of(
                ToolExecutionRequest.builder().id("c1").name("tool_a").arguments("{}").build(),
                ToolExecutionRequest.builder().id("c2").name("tool_b").arguments("{}").build()
        ));
        TurnRecord rec = logger.newRecord();

        List<ToolExecutionResult> results = handler.execute(rec, state);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).toolName()).isEqualTo("tool_a");
        assertThat(results.get(1).toolName()).isEqualTo("tool_b");
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(1).success()).isTrue();
    }

    @Test
    void execute_serialOnlyTool_executedSeparately() {
        registry.register(simpleTool("todo_write", args -> "todo updated"));
        registry.register(simpleTool("read_file", args -> "content"));
        SessionState state = createStateWithPending(List.of(
                ToolExecutionRequest.builder().id("c1").name("read_file").arguments("{}").build(),
                ToolExecutionRequest.builder().id("c2").name("todo_write").arguments("{}").build()
        ));
        TurnRecord rec = logger.newRecord();

        List<ToolExecutionResult> results = handler.execute(rec, state);

        assertThat(results).hasSize(2);
        // Both should succeed, order preserved
        assertThat(results.get(0).toolName()).isEqualTo("read_file");
        assertThat(results.get(1).toolName()).isEqualTo("todo_write");
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(1).success()).isTrue();
    }

    @Test
    void execute_throwingTool_returnsFailureResult() {
        registry.register(new ToolDescriptor(
                "bomb", "throws", ToolPermission.READONLY,
                ToolSpecification.builder().name("bomb").description("throws").build(),
                (args, state, ctx) -> { throw new RuntimeException("kaboom"); }
        ));
        SessionState state = createStateWithPending(List.of(
                ToolExecutionRequest.builder().id("c1").name("bomb").arguments("{}").build()
        ));
        TurnRecord rec = logger.newRecord();

        List<ToolExecutionResult> results = handler.execute(rec, state);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isFalse();
        assertThat(results.get(0).content()).contains("kaboom");
    }

    @Test
    void execute_parallelFailureDoesNotAffectOthers() {
        registry.register(new ToolDescriptor(
                "bomb", "throws", ToolPermission.READONLY,
                ToolSpecification.builder().name("bomb").description("throws").build(),
                (args, state, ctx) -> { throw new RuntimeException("kaboom"); }
        ));
        registry.register(simpleTool("safe_tool", args -> "safe_result"));
        SessionState state = createStateWithPending(List.of(
                ToolExecutionRequest.builder().id("c1").name("bomb").arguments("{}").build(),
                ToolExecutionRequest.builder().id("c2").name("safe_tool").arguments("{}").build()
        ));
        TurnRecord rec = logger.newRecord();

        List<ToolExecutionResult> results = handler.execute(rec, state);

        assertThat(results).hasSize(2);
        // bomb failed
        assertThat(results.get(0).success()).isFalse();
        assertThat(results.get(0).toolName()).isEqualTo("bomb");
        // safe_tool succeeded (isolation)
        assertThat(results.get(1).success()).isTrue();
        assertThat(results.get(1).toolName()).isEqualTo("safe_tool");
    }

    @Test
    void execute_trippedTool_returnsSyntheticError() {
        registry.register(simpleTool("bad_tool", args -> "ok"));
        // Trip the tool via consecutive failures
        for (int i = 0; i < 5; i++) {
            loopDetector.recordToolResult("bad_tool", false);
        }
        assertThat(loopDetector.isToolTripped("bad_tool")).isTrue();

        SessionState state = createStateWithPending(List.of(
                ToolExecutionRequest.builder().id("c1").name("bad_tool").arguments("{}").build()
        ));
        TurnRecord rec = logger.newRecord();

        List<ToolExecutionResult> results = handler.execute(rec, state);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isFalse();
        assertThat(results.get(0).content()).contains("熔断");
    }

    @Test
    void execute_nullArguments_handledGracefully() {
        registry.register(simpleTool("read_file", args -> "content: " + args.size()));
        SessionState state = createStateWithPending(List.of(
                ToolExecutionRequest.builder().id("c1").name("read_file").arguments(null).build()
        ));
        TurnRecord rec = logger.newRecord();

        List<ToolExecutionResult> results = handler.execute(rec, state);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isTrue();
    }

    @Test
    void execute_emptyArguments_handledGracefully() {
        registry.register(simpleTool("read_file", args -> "content"));
        SessionState state = createStateWithPending(List.of(
                ToolExecutionRequest.builder().id("c1").name("read_file").arguments("").build()
        ));
        TurnRecord rec = logger.newRecord();

        List<ToolExecutionResult> results = handler.execute(rec, state);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isTrue();
    }

    @Test
    void execute_mixedParallelAndSerial_allSucceed() {
        registry.register(simpleTool("read_file", args -> "content"));
        registry.register(simpleTool("list_dir", args -> "listing"));
        registry.register(simpleTool("todo_write", args -> "todo set"));
        SessionState state = createStateWithPending(List.of(
                ToolExecutionRequest.builder().id("c1").name("read_file").arguments("{}").build(),
                ToolExecutionRequest.builder().id("c2").name("list_dir").arguments("{}").build(),
                ToolExecutionRequest.builder().id("c3").name("todo_write").arguments("{}").build()
        ));
        TurnRecord rec = logger.newRecord();

        List<ToolExecutionResult> results = handler.execute(rec, state);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).toolName()).isEqualTo("read_file");
        assertThat(results.get(1).toolName()).isEqualTo("list_dir");
        assertThat(results.get(2).toolName()).isEqualTo("todo_write");
        assertThat(results).allMatch(ToolExecutionResult::success);
    }

    @Test
    void execute_parsedJsonArguments_passedToTool() {
        AtomicInteger capturedArgCount = new AtomicInteger(0);
        registry.register(new ToolDescriptor(
                "read_file", "test", ToolPermission.READONLY,
                ToolSpecification.builder().name("read_file").description("test").build(),
                (args, state, ctx) -> {
                    capturedArgCount.set(args.size());
                    return ToolOutcome.success("path: " + args.get("path"));
                }
        ));
        SessionState state = createStateWithPending(List.of(
                ToolExecutionRequest.builder().id("c1").name("read_file")
                        .arguments("{\"path\":\"test.txt\"}").build()
        ));
        TurnRecord rec = logger.newRecord();

        List<ToolExecutionResult> results = handler.execute(rec, state);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(0).content()).contains("test.txt");
        assertThat(capturedArgCount.get()).isEqualTo(1);
    }

    @Test
    void execute_todoWriteActivatesNavigator() {
        registry.register(simpleTool("todo_write", args -> "ok"));
        SessionState state = createStateWithPending(List.of(
                ToolExecutionRequest.builder().id("c1").name("todo_write")
                        .arguments("{}").build()
        ));
        assertThat(state.hasTodoBeenUsed()).isFalse();
        TurnRecord rec = logger.newRecord();

        handler.execute(rec, state);

        assertThat(state.hasTodoBeenUsed()).isTrue();
    }
}
