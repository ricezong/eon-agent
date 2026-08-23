package cn.kong.eon;

import cn.kong.eon.agent.hook.premodel.TodoNavigatorHook;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.context.CompressionEngine;
import cn.kong.eon.context.PairingRepairer;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.loop.LoopDetector;
import cn.kong.eon.model.*;
import cn.kong.eon.store.*;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolOutcome;
import cn.kong.eon.tool.ToolRegistry;
import cn.kong.eon.tool.ToolResultRenderer;
import cn.kong.eon.tool.builtin.GrepTool;
import cn.kong.eon.tool.builtin.ListDirTool;
import cn.kong.eon.tool.builtin.ReadFileTool;
import cn.kong.eon.tool.builtin.TodoWriteTool;
import cn.kong.eon.tool.builtin.WebSearchTool;
import cn.kong.eon.tool.builtin.WriteFileTool;
import cn.kong.eon.tool.builtin.DeleteFileTool;
import cn.kong.eon.tool.builtin.DownloadFileTool;
import cn.kong.eon.tool.builtin.AskQuestionTool;
import cn.kong.eon.tool.builtin.UpdateMemoryTool;
import cn.kong.eon.tool.builtin.WebFetchTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import cn.kong.eon.tool.ToolDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核心逻辑单元测试（不需要 LLM API Key）。
 * 验证：ContextBuilder 分层组装、配对修复、Todo 状态机、工具注册表、压缩引擎。
 * P1 阶段：todo_write 相关测试将在 P2 新工具落地后恢复。
 */
class CoreLogicTest {

    @TempDir
    Path tempDir;

    private AgentConfig config;
    private TodoStore todoStore;
    private MemoryStore memoryStore;
    private ArtifactStore artifactStore;
    private JsonlStore jsonlStore;
    private ToolRegistry toolRegistry;
    private ToolContext toolContext;
    private PairingRepairer pairingRepairer;

    @BeforeEach
    void setUp() {
        config = AgentConfig.loadFromClasspath("config/agent.yaml");
        todoStore = new TodoStore();
        memoryStore = new MemoryStore(tempDir);
        Path sessionDir = tempDir.resolve("test-session");
        artifactStore = new ArtifactStore(sessionDir.resolve("artifacts"));
        jsonlStore = new JsonlStore(sessionDir.resolve("transcript.jsonl"));
        CheckpointStore checkpointStore = new CheckpointStore(sessionDir.resolve("checkpoints"));

        toolRegistry = new ToolRegistry(config.getTools().whitelist);
        toolRegistry.register(WebSearchTool.descriptor(config.getWebSearch().apiKey));
        toolRegistry.register(ReadFileTool.descriptor());
        toolRegistry.register(WriteFileTool.descriptor());
        toolRegistry.register(DeleteFileTool.descriptor());
        toolRegistry.register(ListDirTool.descriptor());
        toolRegistry.register(DownloadFileTool.descriptor());
        toolRegistry.register(GrepTool.descriptor());
        toolRegistry.register(TodoWriteTool.descriptor());
        toolRegistry.register(AskQuestionTool.descriptor());
        toolRegistry.register(UpdateMemoryTool.descriptor());
        toolRegistry.register(WebFetchTool.descriptor());

        toolContext = new ToolContext(
                todoStore, artifactStore, memoryStore, jsonlStore, checkpointStore,
                tempDir.toString());

        pairingRepairer = new PairingRepairer();
    }

    @Test
    void should_assemble_context_in_new_order() {
        SessionState state = SessionState.create("test-neworder", "搜索测试");
        state.setTodoBeenUsed(true);
        todoStore.replaceAll(List.of(
                TodoItem.of("t1", "搜索资源", "high"),
                TodoItem.of("t2", "提取链接", "high"),
                TodoItem.of("t3", "整理结果", "high")
        ), 0);

        TodoNavigatorHook navigator = new TodoNavigatorHook(todoStore);
        cn.kong.eon.context.ContextBuilder ctx = new cn.kong.eon.context.ContextBuilder();
        cn.kong.eon.agent.hook.HookResult result = navigator.beforeModelCall(state, ctx);

        assertThat(navigator.isActive(state)).isTrue();
        assertThat(result.isContinue()).isTrue();
    }

    @Test
    void should_keep_system_prompt_at_index_zero_for_kv_cache() {
        SessionState state = SessionState.create("test-cache", "test");

        cn.kong.eon.context.ContextBuilder ctx = new cn.kong.eon.context.ContextBuilder();
        ctx.setSystemPrompt("你是 Eon Agent");
        List<ChatMessage> messages = ctx.build();

        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) messages.get(0)).text()).contains("Eon");
    }

    @Test
    void should_render_todo_into_navigator() {
        SessionState state = SessionState.create("test-merge", "用户原始请求");
        state.setTodoBeenUsed(true);
        todoStore.replaceAll(List.of(TodoItem.of("t1", "任务A", "high")), 0);

        TodoNavigatorHook navigator = new TodoNavigatorHook(todoStore);
        cn.kong.eon.context.ContextBuilder ctx = new cn.kong.eon.context.ContextBuilder();
        navigator.beforeModelCall(state, ctx);

        ctx.setSystemPrompt("system");
        List<ChatMessage> messages = ctx.build();
        UserMessage navMsg = messages.stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> (UserMessage) m)
                .filter(um -> "navigator".equals(um.name()))
                .findFirst()
                .orElseThrow();
        assertThat(navMsg.singleText()).contains("任务A");
        assertThat(navMsg.singleText()).doesNotContain("用户原始请求");
    }

    @Test
    void should_repair_orphan_tool_result() {
        List<ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(SystemMessage.from("system"));
        messages.add(UserMessage.from("user"));
        messages.add(ToolExecutionResultMessage.from("orphan-id", "web_search", "orphan result"));

        List<ChatMessage> repaired = pairingRepairer.repair(messages);

        long toolResultCount = repaired.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .count();
        assertThat(toolResultCount).isZero();
    }

    @Test
    void should_insert_synthetic_result_for_orphan_tool_use() {
        List<ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(SystemMessage.from("system"));
        messages.add(UserMessage.from("user"));
        messages.add(AiMessage.from(ToolExecutionRequest.builder()
                .id("call-001")
                .name("web_search")
                .arguments("{\"query\":\"test\"}")
                .build()));

        List<ChatMessage> repaired = pairingRepairer.repair(messages);

        long syntheticCount = repaired.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text())
                .filter(text -> text.contains("[SYNTHETIC]"))
                .count();
        assertThat(syntheticCount).isEqualTo(1);
    }

    @Test
    void should_validate_single_focus_constraint() {
        List<TodoItem> todos = List.of(
                TodoItem.of("t1", "task1", "high"),
                TodoItem.of("t2", "task2", "high")
        );
        todos.get(0).setStatus(TodoStatus.IN_PROGRESS);
        todos.get(1).setStatus(TodoStatus.IN_PROGRESS);

        boolean valid = todoStore.validateSingleFocus(todos);

        assertThat(valid).isFalse();
    }

    @Test
    void should_validate_dependency_constraint() {
        List<TodoItem> todos = List.of(
                TodoItem.of("t1", "task1", "high"),
                TodoItem.of("t2", "task2", "high")
        );
        todos.get(0).setStatus(TodoStatus.PENDING);
        todos.get(1).setStatus(TodoStatus.IN_PROGRESS);
        todos.get(1).setDependsOn(List.of("t1"));

        boolean valid = todoStore.validateDependencies(todos);

        assertThat(valid).isFalse();
    }

    @Test
    void should_register_web_search_in_p1_baseline() {
        assertThat(toolRegistry.get("web_search")).isNotNull();
    }

    @Test
    void should_classify_tool_permissions() {
        assertThat(toolRegistry.isReadonly("web_search")).isTrue();
        assertThat(toolRegistry.isDestructive("web_search")).isFalse();
    }

    @Test
    void should_render_tool_result_with_semantic_annotation() {
        ToolResultRenderer renderer = new ToolResultRenderer(artifactStore);
        SessionState state = SessionState.create("test-render", "test");

        String rendered = renderer.render("web_search",
                ToolOutcome.success("搜索完成，找到 5 条结果"), state);

        assertThat(rendered).contains("[工具结果] web_search");
        assertThat(rendered).contains("搜索完成，找到 5 条结果");
    }

    @Test
    void should_save_large_result_as_artifact() {
        ToolResultRenderer renderer = new ToolResultRenderer(artifactStore);
        SessionState state = SessionState.create("test-artifact", "test");
        String largeContent = "x".repeat(8001);

        String rendered = renderer.render("web_fetch",
                ToolOutcome.success(largeContent), state);

        assertThat(rendered).contains("artifact://art_");
        assertThat(rendered).contains("[工具结果] web_fetch");
        assertThat(artifactStore.listAll()).hasSize(1);
    }

    @Test
    void should_keep_full_content_below_threshold() {
        ToolResultRenderer renderer = new ToolResultRenderer(artifactStore);
        SessionState state = SessionState.create("test-full", "test");
        String content = "x".repeat(2000);

        String rendered = renderer.render("web_fetch",
                ToolOutcome.success(content), state);

        assertThat(rendered).doesNotContain("artifact://");
        assertThat(rendered).contains("x".repeat(2000));
        assertThat(rendered).doesNotContain("中间内容省略");
        assertThat(artifactStore.listAll()).isEmpty();
    }

    @Test
    void should_check_all_completed() {
        todoStore.replaceAll(List.of(
                TodoItem.of("t1", "task1", "high"),
                TodoItem.of("t2", "task2", "high")
        ), 0);

        assertThat(todoStore.allCompleted()).isFalse();

        List<TodoItem> todos = todoStore.getAll();
        todos.forEach(t -> t.setStatus(TodoStatus.COMPLETED));
        todoStore.replaceAll(todos, 1);

        assertThat(todoStore.allCompleted()).isTrue();
    }

    // ==================== 连续失败熔断器测试 ====================

    @Test
    void should_warn_after_consecutive_failures() {
        LoopDetector detector = new LoopDetector(3, 5, 6, 3, 5);

        LoopDetector.DetectionResult r1 = detector.recordToolResult("web_search", false);
        LoopDetector.DetectionResult r2 = detector.recordToolResult("web_search", false);
        LoopDetector.DetectionResult r3 = detector.recordToolResult("web_search", false);

        assertThat(r1.shouldWarn()).isFalse();
        assertThat(r2.shouldWarn()).isFalse();
        assertThat(r3.shouldWarn()).isTrue();
        assertThat(r3.message()).contains("blocked");
    }

    @Test
    void should_trip_after_failure_stop_threshold() {
        LoopDetector detector = new LoopDetector(3, 5, 6, 3, 5);

        LoopDetector.DetectionResult result = null;
        for (int i = 0; i < 5; i++) {
            result = detector.recordToolResult("web_search", false);
        }

        assertThat(result.shouldStop()).isTrue();
        assertThat(result.message()).contains("熔断");
    }

    @Test
    void should_reset_failures_on_success() {
        LoopDetector detector = new LoopDetector(3, 5, 6, 3, 5);

        detector.recordToolResult("web_search", false);
        detector.recordToolResult("web_search", false);
        detector.recordToolResult("web_search", true);
        LoopDetector.DetectionResult r1 = detector.recordToolResult("web_search", false);
        LoopDetector.DetectionResult r2 = detector.recordToolResult("web_search", false);

        assertThat(r1.shouldWarn()).isFalse();
        assertThat(r2.shouldWarn()).isFalse();
    }

    @Test
    void should_not_escalate_cross_tool_failures() {
        LoopDetector detector = new LoopDetector(3, 5, 6, 3, 5);

        detector.recordToolResult("web_search", false);
        detector.recordToolResult("web_search", false);
        detector.recordToolResult("web_fetch", false);
        detector.recordToolResult("web_fetch", false);
        LoopDetector.DetectionResult r5 = detector.recordToolResult("web_fetch", false);

        assertThat(r5.shouldStop()).isFalse();
        assertThat(r5.shouldWarn()).isTrue();
        assertThat(detector.isToolTripped("web_fetch")).isFalse();
        assertThat(detector.isToolTripped("web_search")).isFalse();
    }

    @Test
    void should_trip_individual_tool_after_failures() {
        LoopDetector detector = new LoopDetector(3, 5, 6, 3, 5);

        for (int i = 0; i < 5; i++) {
            detector.recordToolResult("web_search", false);
        }

        assertThat(detector.isToolTripped("web_search")).isTrue();
        assertThat(detector.isToolTripped("web_fetch")).isFalse();
    }

    // ==================== P2 文件工具测试 ====================

    @Test
    void should_read_file_raw_content() throws Exception {
        Path testFile = tempDir.resolve("test-read.txt");
        Files.writeString(testFile, "line1\nline2\nline3");

        ToolDescriptor desc = ReadFileTool.descriptor();
        var state = SessionState.create("test-rf", "test");
        Map<String, Object> args = Map.of("target_file", testFile.toAbsolutePath().toString());
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("line1");
        assertThat(result.content()).contains("line2");
        assertThat(result.content()).contains("line3");
        // 不应包含行号前缀
        assertThat(result.content()).doesNotContain("|line1");
    }

    @Test
    void should_read_file_with_offset_and_limit() throws Exception {
        Path testFile = tempDir.resolve("test-offset.txt");
        Files.writeString(testFile, "a\nb\nc\nd\ne\nf");

        ToolDescriptor desc = ReadFileTool.descriptor();
        var state = SessionState.create("test-off", "test");
        Map<String, Object> args = Map.of("target_file", testFile.toAbsolutePath().toString(), "offset", 3, "limit", 2);
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("c");
        assertThat(result.content()).contains("d");
        assertThat(result.content()).doesNotContain("a");
        assertThat(result.content()).doesNotContain("f");
    }

    @Test
    void should_return_empty_for_empty_file() throws Exception {
        Path testFile = tempDir.resolve("empty.txt");
        Files.writeString(testFile, "");

        ToolDescriptor desc = ReadFileTool.descriptor();
        var state = SessionState.create("test-empty", "test");
        Map<String, Object> args = Map.of("target_file", testFile.toAbsolutePath().toString());
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("文件为空。");
    }

    @Test
    void should_read_artifact_reference() {
        // 先存一个 artifact
        String fullContent = "这是完整内容，超过阈值被截断了。".repeat(200);
        ArtifactRef ref = artifactStore.save("reader_content", fullContent, "摘要");
        String refId = ref.getRefId();

        // 用 read_file 读回 artifact://refId
        ToolDescriptor desc = ReadFileTool.descriptor();
        var state = SessionState.create("test-art", "test");
        Map<String, Object> args = Map.of("target_file", "artifact://" + refId);
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo(fullContent);
    }

    @Test
    void should_fail_on_nonexistent_artifact_reference() {
        ToolDescriptor desc = ReadFileTool.descriptor();
        var state = SessionState.create("test-art-nf", "test");
        Map<String, Object> args = Map.of("target_file", "artifact://art_999");
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("找不到 artifact 引用");
    }

    @Test
    void should_write_new_file() throws Exception {
        Path testFile = tempDir.resolve("new-file.txt");

        ToolDescriptor desc = WriteFileTool.descriptor();
        var state = SessionState.create("test-wf", "test");
        Map<String, Object> args = Map.of("file_path", testFile.toAbsolutePath().toString(), "contents", "hello world");
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo("hello world");
    }

    @Test
    void should_overwrite_existing_file_directly() throws Exception {
        Path testFile = tempDir.resolve("existing.txt");
        Files.writeString(testFile, "original");

        // 无需先读即可覆盖
        ToolDescriptor desc = WriteFileTool.descriptor();
        var state = SessionState.create("test-block", "test");
        Map<String, Object> args = Map.of("file_path", testFile.toAbsolutePath().toString(), "contents", "overwritten");
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo("overwritten");
    }

    @Test
    void should_write_after_read() throws Exception {
        Path testFile = tempDir.resolve("read-then-write.txt");
        Files.writeString(testFile, "original");

        // 先读
        var readDesc = ReadFileTool.descriptor();
        var state1 = SessionState.create("test-rw", "test");
        readDesc.getExecutor().execute(
                Map.<String, Object>of("target_file", testFile.toAbsolutePath().toString()), state1, toolContext);

        // 再写
        ToolDescriptor desc = WriteFileTool.descriptor();
        Map<String, Object> args = Map.of("file_path", testFile.toAbsolutePath().toString(), "contents", "updated");
        ToolOutcome result = desc.getExecutor().execute(args, state1, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo("updated");
    }

    @Test
    void should_delete_file() throws Exception {
        Path testFile = tempDir.resolve("delete-me.txt");
        Files.writeString(testFile, "bye");

        ToolDescriptor desc = DeleteFileTool.descriptor();
        var state = SessionState.create("test-df", "test");
        Map<String, Object> args = Map.of("target_file", testFile.toAbsolutePath().toString(),
                "explanation", "test deletion");
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(Files.exists(testFile)).isFalse();
    }

    @Test
    void should_delete_file_graceful_not_found() {
        ToolDescriptor desc = DeleteFileTool.descriptor();
        var state = SessionState.create("test-nf", "test");
        Map<String, Object> args = Map.of("target_file", "nonexistent-xyz.txt");
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("文件不存在");
    }

    @Test
    void should_list_dir_basic() throws Exception {
        Path dir = tempDir.resolve("listdir");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a.txt"), "a");
        Files.writeString(dir.resolve("b.md"), "b");
        Files.writeString(dir.resolve(".hidden"), "h");

        ToolDescriptor desc = ListDirTool.descriptor();
        var state = SessionState.create("test-ld", "test");
        Map<String, Object> args = Map.of("target_directory", dir.toAbsolutePath().toString());
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("a.txt");
        assertThat(result.content()).contains("b.md");
        assertThat(result.content()).doesNotContain(".hidden");
    }

    @Test
    void should_grep_search_single_file() throws Exception {
        Path dir = tempDir.resolve("grepdir");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("find.txt"), "hello world\nfoo bar\nhello again");

        ToolDescriptor desc = GrepTool.descriptor();
        var state = SessionState.create("test-grep", "test");
        Map<String, Object> args = Map.of("pattern", "hello", "path", dir.resolve("find.txt").toAbsolutePath().toString());
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("hello world");
        assertThat(result.content()).contains("hello again");
        assertThat(result.content()).contains("2 处匹配");
    }

    @Test
    void should_grep_search_directory() throws Exception {
        Path dir = tempDir.resolve("grepmulti");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a.txt"), "apple\nbanana\napple pie");
        Files.writeString(dir.resolve("b.txt"), "no match here\napple juice");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/c.txt"), "apple\nnothing");

        ToolDescriptor desc = GrepTool.descriptor();
        var state = SessionState.create("test-grep-dir", "test");
        Map<String, Object> args = Map.of("pattern", "apple", "path", dir.toAbsolutePath().toString());
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("a.txt");
        assertThat(result.content()).contains("b.txt");
        assertThat(result.content()).contains("c.txt");
        assertThat(result.content()).contains("4 处匹配");
    }

    @Test
    void should_grep_case_insensitive() throws Exception {
        Path dir = tempDir.resolve("grepci");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ci.txt"), "Hello World\nHELLO there\nhello\nGoodbye");

        ToolDescriptor desc = GrepTool.descriptor();
        var state = SessionState.create("test-ci", "test");
        Map<String, Object> args = Map.of(
                "pattern", "hello",
                "path", dir.resolve("ci.txt").toAbsolutePath().toString(),
                "case_insensitive", "true");
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Hello World");
        assertThat(result.content()).contains("HELLO there");
        assertThat(result.content()).contains("hello");
        assertThat(result.content()).contains("3 处匹配");
    }

    @Test
    void should_grep_with_context_lines() throws Exception {
        Path dir = tempDir.resolve("grepctx");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ctx.txt"), "line1\nline2\nTARGET\nline4\nline5");

        ToolDescriptor desc = GrepTool.descriptor();
        var state = SessionState.create("test-ctx", "test");
        Map<String, Object> args = Map.of(
                "pattern", "TARGET",
                "path", dir.resolve("ctx.txt").toAbsolutePath().toString(),
                "context_lines", "2");
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        // 上下文应包含 line1, line2, TARGET, line4, line5
        assertThat(result.content()).contains("line1");
        assertThat(result.content()).contains("line2");
        assertThat(result.content()).contains("TARGET");
        assertThat(result.content()).contains("line4");
        assertThat(result.content()).contains("line5");
    }

    @Test
    void should_grep_regex_pattern() throws Exception {
        Path dir = tempDir.resolve("grepregex");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("rx.txt"), "test123\ntest456\nnope_match\ntest789");

        ToolDescriptor desc = GrepTool.descriptor();
        var state = SessionState.create("test-regex", "test");
        Map<String, Object> args = Map.of(
                "pattern", "test\\d+",
                "path", dir.resolve("rx.txt").toAbsolutePath().toString());
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("test123");
        assertThat(result.content()).contains("test456");
        assertThat(result.content()).contains("test789");
        assertThat(result.content()).contains("3 处匹配");
    }

    @Test
    void should_todo_write_merge_mode() throws Exception {
        // 先全量替换
        toolContext.todoStore().replaceAll(List.of(
                TodoItem.of("t1", "task1", "high"),
                TodoItem.of("t2", "task2", "high")
        ), 0);

        // merge 更新 t1 状态 + 新增 t3
        ToolDescriptor desc = TodoWriteTool.descriptor();
        var state = SessionState.create("test-merge", "test");
        var todos = List.of(
                Map.of("id", "t1", "content", "task1 updated", "status", "completed"),
                Map.of("id", "t3", "content", "task3 new", "status", "pending")
        );
        Map<String, Object> args = Map.of("todos", todos, "merge", true);
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(toolContext.todoStore().getAll()).hasSize(3);
        assertThat(toolContext.todoStore().get("t1").getStatus()).isEqualTo(TodoStatus.COMPLETED);
        assertThat(toolContext.todoStore().get("t3")).isNotNull();
    }

    @Test
    void should_todo_write_replace_mode() throws Exception {
        toolContext.todoStore().replaceAll(List.of(
                TodoItem.of("old1", "old task", "high")
        ), 0);

        ToolDescriptor desc = TodoWriteTool.descriptor();
        var state = SessionState.create("test-repl", "test");
        var todos = List.of(
                Map.of("id", "n1", "content", "new task 1", "status", "pending"),
                Map.of("id", "n2", "content", "new task 2", "status", "in_progress")
        );
        Map<String, Object> args = Map.of("todos", todos, "merge", false);
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(toolContext.todoStore().getAll()).hasSize(2);
        assertThat(toolContext.todoStore().get("old1")).isNull();
        assertThat(toolContext.todoStore().get("n1")).isNotNull();
    }

    @Test
    void should_todo_write_reject_multiple_in_progress() {
        ToolDescriptor desc = TodoWriteTool.descriptor();
        var state = SessionState.create("test-focus", "test");
        var todos = List.of(
                Map.of("id", "t1", "content", "task1", "status", "in_progress"),
                Map.of("id", "t2", "content", "task2", "status", "in_progress")
        );
        Map<String, Object> args = Map.of("todos", todos, "merge", false);
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("in_progress");
    }

    @Test
    void should_register_all_11_tools() {
        // 终态 11 个本地工具
        assertThat(toolRegistry.getAll()).hasSize(11);
        assertThat(toolRegistry.get("read_file")).isNotNull();
        assertThat(toolRegistry.get("write")).isNotNull();
        assertThat(toolRegistry.get("delete_file")).isNotNull();
        assertThat(toolRegistry.get("list_dir")).isNotNull();
        assertThat(toolRegistry.get("download_file")).isNotNull();
        assertThat(toolRegistry.get("grep")).isNotNull();
        assertThat(toolRegistry.get("todo_write")).isNotNull();
        assertThat(toolRegistry.get("AskQuestion")).isNotNull();
        assertThat(toolRegistry.get("update_memory")).isNotNull();
        assertThat(toolRegistry.get("web_fetch")).isNotNull();
        assertThat(toolRegistry.get("web_search")).isNotNull();
    }

    // ==================== P3 交互/记忆/网页工具测试 ====================

    @Test
    void should_update_memory_create() {
        toolContext.memoryStore().loadAll().forEach(m -> toolContext.memoryStore().delete(m.id));

        ToolDescriptor desc = UpdateMemoryTool.descriptor();
        var state = SessionState.create("test-mem-create", "test");
        Map<String, Object> args = Map.of(
                "action", "create",
                "title", "Test Memory",
                "knowledge_to_store", "This is a test memory content.");
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("记忆创建成功");
        assertThat(result.content()).contains("Test Memory");

        var memories = toolContext.memoryStore().loadAll();
        assertThat(memories).hasSize(1);
        assertThat(memories.get(0).title).isEqualTo("Test Memory");
    }

    @Test
    void should_update_memory_update() {
        // 先创建
        var item = toolContext.memoryStore().create("Original", "Original content");

        ToolDescriptor desc = UpdateMemoryTool.descriptor();
        var state = SessionState.create("test-mem-update", "test");
        Map<String, Object> args = Map.of(
                "action", "update",
                "existing_knowledge_id", item.id,
                "title", "Updated Title",
                "knowledge_to_store", "Updated content");
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("记忆更新成功");

        var updated = toolContext.memoryStore().loadAll().stream()
                .filter(m -> m.id.equals(item.id)).findFirst().orElseThrow();
        assertThat(updated.title).isEqualTo("Updated Title");
        assertThat(updated.content).isEqualTo("Updated content");
    }

    @Test
    void should_update_memory_delete() {
        var item = toolContext.memoryStore().create("ToDelete", "Will be deleted");

        ToolDescriptor desc = UpdateMemoryTool.descriptor();
        var state = SessionState.create("test-mem-delete", "test");
        Map<String, Object> args = Map.of(
                "action", "delete",
                "existing_knowledge_id", item.id);
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("记忆删除成功");
        assertThat(toolContext.memoryStore().loadAll().stream()
                .anyMatch(m -> m.id.equals(item.id))).isFalse();
    }

    @Test
    void should_update_memory_fail_without_id_for_update() {
        ToolDescriptor desc = UpdateMemoryTool.descriptor();
        var state = SessionState.create("test-mem-noid", "test");
        Map<String, Object> args = Map.of(
                "action", "update",
                "title", "No ID");
        ToolOutcome result = desc.getExecutor().execute(args, state, toolContext);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("existing_knowledge_id");
    }

    @Test
    void should_render_memory_references() {
        var item = toolContext.memoryStore().create("User Prefers Vim", "User likes vim editor for coding");

        String input = "I'll use vim [[memory:" + item.id + "]] as you prefer.";
        String rendered = toolContext.memoryStore().renderReferences(input);

        assertThat(rendered).contains("User Prefers Vim");
        assertThat(rendered).doesNotContain("[[memory:");
    }

    @Test
    void should_render_memory_references_preserves_unknown() {
        String input = "Reference to [[memory:nonexistent_id]] should stay.";
        String rendered = toolContext.memoryStore().renderReferences(input);

        // Unknown memory reference preserved as-is
        assertThat(rendered).contains("[[memory:nonexistent_id]");
    }

    @Test
    void should_ask_question_with_mock_scanner() {
        // Mock scanner that returns "1" (selecting first option)
        Scanner mockScanner = new Scanner("1\n");
        AskQuestionTool tool = new AskQuestionTool(mockScanner);
        ToolDescriptor desc = AskQuestionTool.descriptor();

        // Can't use the static descriptor's executor (it creates its own Scanner),
        // so test via direct execute
        var state = SessionState.create("test-ask", "test");
        var options = List.of(
                Map.of("id", "opt1", "label", "Option A"),
                Map.of("id", "opt2", "label", "Option B")
        );
        var questions = List.of(
                Map.of("id", "q1", "prompt", "Which option?", "options", options)
        );
        Map<String, Object> args = Map.of("questions", questions, "title", "Test Question");

        // Use the tool instance directly
        ToolOutcome result = tool.execute(args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("q1:");
        assertThat(result.content()).contains("opt1");
    }

    @Test
    void should_ask_question_reject_insufficient_options() {
        AskQuestionTool tool = new AskQuestionTool(new Scanner(""));
        var state = SessionState.create("test-ask-fail", "test");
        var options = List.of(
                Map.of("id", "only_one", "label", "Solo")
        );
        var questions = List.of(
                Map.of("id", "q1", "prompt", "Only one option?", "options", options)
        );
        Map<String, Object> args = Map.of("questions", questions);

        ToolOutcome result = tool.execute(args, state, toolContext);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("至少需要 2 个选项");
    }

    // ===== P4: 压缩引擎升级测试 =====

    /**
     * 测试用 LLM 客户端：捕获最后一次 prompt，返回固定 mock 响应。
     */
    private static class CapturingLlmClient extends LlmClient {
        private String lastPrompt = "";
        private final String mockResponse;
        private int callCount = 0;

        CapturingLlmClient(String mockResponse) {
            super(AgentConfig.loadFromClasspath("config/agent.yaml"));
            this.mockResponse = mockResponse;
        }

        @Override
        public LlmResponse chat(List<ChatMessage> messages, List<ToolSpecification> tools) {
            callCount++;
            for (ChatMessage msg : messages) {
                if (msg instanceof UserMessage um) {
                    lastPrompt = um.singleText();
                    break;
                }
            }
            return LlmResponse.of(AiMessage.from(mockResponse), new TokenUsage(), "STOP");
        }

        String getLastPrompt() { return lastPrompt; }
    }

    @Test
    void should_summarize_use_5_segment_template() {
        // 验证摘要提示词包含 5 段标题
        CapturingLlmClient llm = new CapturingLlmClient("mock summary with 5 segments");
        CompressionEngine engine = new CompressionEngine(
                0.65, 0.82, 0.95, 80, 30, 50000, 2000, llm, "/tmp/transcript.jsonl");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("user message 1"));
        messages.add(AiMessage.from("assistant response 1"));
        messages.add(UserMessage.from("user message 2"));
        messages.add(AiMessage.from("assistant response 2"));
        messages.add(UserMessage.from("user message 3"));
        messages.add(AiMessage.from("assistant response 3"));
        messages.add(UserMessage.from("user message 4"));
        messages.add(AiMessage.from("assistant response 4"));
        messages.add(UserMessage.from("user message 5"));
        messages.add(AiMessage.from("assistant response 5"));

        CompressionState state = new CompressionState();
        engine.compress(messages, state, 0.96, 3);

        String prompt = llm.getLastPrompt();
        assertThat(prompt).contains("Primary Request and Intent");
        assertThat(prompt).contains("Key Context and Decisions");
        assertThat(prompt).contains("User Preferences and Updates");
        assertThat(prompt).contains("Pending Tasks and Current Work");
        assertThat(prompt).contains("All User Messages and Transcript");
    }

    @Test
    void should_summarize_inject_transcript_path() {
        // 验证摘要第 5 段注入 transcript 路径
        String testPath = "/data/session_123/transcript.jsonl";
        CapturingLlmClient llm = new CapturingLlmClient("mock summary");
        CompressionEngine engine = new CompressionEngine(
                0.65, 0.82, 0.95, 80, 30, 50000, 2000, llm, testPath);

        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(UserMessage.from("msg " + i));
            messages.add(AiMessage.from("resp " + i));
        }

        CompressionState state = new CompressionState();
        engine.compress(messages, state, 0.96, 3);

        String prompt = llm.getLastPrompt();
        assertThat(prompt).contains(testPath);
        assertThat(prompt).contains("读取文件工具");
    }

    @Test
    void should_summarize_incremental_not_concatenate() {
        // 验证增量摘要：旧摘要 + 被裁剪对话一起送 LLM（非字符串拼接）
        String mockNewSummary = "new merged summary";
        CapturingLlmClient llm = new CapturingLlmClient(mockNewSummary);
        CompressionEngine engine = new CompressionEngine(
                0.65, 0.82, 0.95, 80, 30, 50000, 2000, llm, "/tmp/transcript.jsonl");

        // 第一次摘要
        List<ChatMessage> messages1 = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages1.add(UserMessage.from("old msg " + i));
            messages1.add(AiMessage.from("old resp " + i));
        }
        CompressionState state = new CompressionState();
        engine.compress(messages1, state, 0.96, 3);
        String firstSummary = state.getLastSummary();
        assertThat(firstSummary).isEqualTo(mockNewSummary);

        // 第二次摘要（有旧摘要）
        List<ChatMessage> messages2 = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages2.add(UserMessage.from("new msg " + i));
            messages2.add(AiMessage.from("new resp " + i));
        }
        // 重置 summarizedUpToIndex 以允许再次摘要
        state.setSummarizedUpToIndex(-1);
        engine.compress(messages2, state, 0.96, 3);

        String prompt = llm.getLastPrompt();
        // 验证旧摘要作为输入的一部分（非拼接结果）
        assertThat(prompt).contains("=== 旧摘要（如有） ===");
        assertThat(prompt).contains(mockNewSummary);
        assertThat(prompt).contains("=== 本次被裁剪的对话 ===");
        // 验证最终摘要不是拼接
        assertThat(state.getLastSummary()).isEqualTo(mockNewSummary);
    }

    @Test
    void should_compress_by_turn_count() {
        // 验证轮数触发：水位低于 snip 阈值时，仅 Snip 不做 Summarize
        CapturingLlmClient llm = new CapturingLlmClient("turn-triggered summary");
        CompressionEngine engine = new CompressionEngine(
                0.65, 0.82, 0.95, 80, 30, 50000, 2000, llm, "/tmp/transcript.jsonl");

        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            messages.add(UserMessage.from("msg " + i));
            messages.add(AiMessage.from("resp " + i));
        }

        CompressionState state = new CompressionState();
        // 水位 0.5（低于 snip 0.65）：compressByTurnCount 不应执行 Summarize
        engine.compressByTurnCount(messages, state, 0.5, 3);

        // 验证消息未被删除（Summarize 未执行）
        assertThat(messages.size()).isEqualTo(24);
        assertThat(state.getLastSummary()).isNull();
        assertThat(llm.callCount).isEqualTo(0);
    }

    @Test
    void should_config_have_summarize_turns() {
        // 验证 AgentConfig 正确读取 summarize_turns
        assertThat(config.getSummarizeTurns()).isEqualTo(6);
    }

    // ===== P4: 并行工具执行测试 =====

    @Test
    void should_execute_multiple_tools_in_parallel() throws Exception {
        // 验证多个独立工具并行执行：结果按原顺序返回
        // 创建两个临时文件用于 read_file 并行
        Path file1 = tempDir.resolve("file1.txt");
        Path file2 = tempDir.resolve("file2.txt");
        Files.writeString(file1, "content of file1");
        Files.writeString(file2, "content of file2");

        // 用 ToolExecutionHandler 直接测试
        LoopDetector loopDetector = new LoopDetector(3, 5, 6, 3, 5);
        ToolResultRenderer renderer = new ToolResultRenderer(artifactStore);
        cn.kong.eon.agent.support.TurnLogger turnLogger = new cn.kong.eon.agent.support.TurnLogger(config);
        cn.kong.eon.agent.support.ToolExecutionHandler handler =
                new cn.kong.eon.agent.support.ToolExecutionHandler(
                        toolRegistry, renderer, toolContext, turnLogger, loopDetector, 4);

        var state = SessionState.create("test-parallel", "test");
        // 构造两个 read_file 请求
        List<ToolExecutionRequest> requests = List.of(
                ToolExecutionRequest.builder()
                        .id("req1").name("read_file")
                        .arguments("{\"target_file\":\"" + file1.toString().replace("\\", "/") + "\"}")
                        .build(),
                ToolExecutionRequest.builder()
                        .id("req2").name("read_file")
                        .arguments("{\"target_file\":\"" + file2.toString().replace("\\", "/") + "\"}")
                        .build()
        );
        state.setPendingToolCalls(requests);

        var rec = turnLogger.newRecord();
        turnLogger.turnHeader(rec, state);
        List<cn.kong.eon.model.ToolExecutionResult> results = handler.execute(rec, state);

        assertThat(results).hasSize(2);
        // 结果按原顺序：file1 先，file2 后
        assertThat(results.get(0).content()).contains("content of file1");
        assertThat(results.get(1).content()).contains("content of file2");
        // 两个都应成功
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(1).success()).isTrue();

        handler.shutdown();
    }

    @Test
    void should_serial_only_tools_not_parallel() throws Exception {
        // 验证串行豁免清单中的工具不被并行执行
        // 用 todo_write（串行）+ read_file（并行）混合
        Path file1 = tempDir.resolve("serial_test.txt");
        Files.writeString(file1, "test content");

        LoopDetector loopDetector = new LoopDetector(3, 5, 6, 3, 5);
        ToolResultRenderer renderer = new ToolResultRenderer(artifactStore);
        cn.kong.eon.agent.support.TurnLogger turnLogger = new cn.kong.eon.agent.support.TurnLogger(config);
        cn.kong.eon.agent.support.ToolExecutionHandler handler =
                new cn.kong.eon.agent.support.ToolExecutionHandler(
                        toolRegistry, renderer, toolContext, turnLogger, loopDetector, 4);

        var state = SessionState.create("test-serial", "test");
        // todo_write（串行豁免） + read_file（可并行）
        List<ToolExecutionRequest> requests = List.of(
                ToolExecutionRequest.builder()
                        .id("req1").name("todo_write")
                        .arguments("{\"todos\":[{\"id\":\"t1\",\"content\":\"task1\",\"status\":\"pending\",\"priority\":\"high\"}]}")
                        .build(),
                ToolExecutionRequest.builder()
                        .id("req2").name("read_file")
                        .arguments("{\"target_file\":\"" + file1.toString().replace("\\", "/") + "\"}")
                        .build()
        );
        state.setPendingToolCalls(requests);

        var rec = turnLogger.newRecord();
        turnLogger.turnHeader(rec, state);
        List<cn.kong.eon.model.ToolExecutionResult> results = handler.execute(rec, state);

        assertThat(results).hasSize(2);
        // todo_write 结果在 index 0，read_file 在 index 1
        assertThat(results.get(0).toolName()).isEqualTo("todo_write");
        assertThat(results.get(1).toolName()).isEqualTo("read_file");
        // 两个都应成功
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(1).success()).isTrue();

        handler.shutdown();
    }
}
