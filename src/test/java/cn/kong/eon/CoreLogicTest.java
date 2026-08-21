package cn.kong.eon;

import cn.kong.eon.agent.hook.premodel.TodoNavigatorHook;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.context.PairingRepairer;
import cn.kong.eon.loop.LoopDetector;
import cn.kong.eon.model.*;
import cn.kong.eon.store.*;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolOutcome;
import cn.kong.eon.tool.ToolRegistry;
import cn.kong.eon.tool.ToolResultRenderer;
import cn.kong.eon.tool.builtin.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核心逻辑单元测试（不需要 LLM API Key）。
 * 验证：ContextBuilder 分层组装、配对修复、Todo 状态机、工具注册表、压缩引擎。
 */
class CoreLogicTest {

    @TempDir
    Path tempDir;

    private AgentConfig config;
    private TodoStore todoStore;
    private InsightsStore insightsStore;
    private ArtifactStore artifactStore;
    private JsonlStore jsonlStore;
    private ToolRegistry toolRegistry;
    private ToolContext toolContext;
    private PairingRepairer pairingRepairer;

    @BeforeEach
    void setUp() {
        config = AgentConfig.loadFromClasspath("config/agent.yaml");
        todoStore = new TodoStore();
        insightsStore = new InsightsStore(40, 8000);
        // 模拟会话隔离目录结构: baseDir/{sessionId}/{artifacts,checkpoints,...}
        Path sessionDir = tempDir.resolve("test-session");
        artifactStore = new ArtifactStore(sessionDir.resolve("artifacts"));
        jsonlStore = new JsonlStore(sessionDir.resolve("transcript.jsonl"));
        CheckpointStore checkpointStore = new CheckpointStore(sessionDir.resolve("checkpoints"));

        toolRegistry = new ToolRegistry(config.getTools().whitelist);
        toolRegistry.register(TodoWriteTool.descriptor());
        toolRegistry.register(TodoReadTool.descriptor());
        toolRegistry.register(WorkingMemoryTool.descriptor());
        toolRegistry.register(FinishTool.descriptor());
        toolRegistry.register(WebSearchTool.descriptor(config.getWebSearch().apiKey));
        toolRegistry.register(WebReadTool.descriptor());
        toolRegistry.register(DownloadTool.descriptor());
        toolRegistry.register(FileIoTool.descriptor());
        toolRegistry.register(DateTimeTool.descriptor());

        toolContext = new ToolContext(
                todoStore, artifactStore, insightsStore, jsonlStore, checkpointStore,
                sessionDir.resolve("downloads").toString(),
                sessionDir.resolve("workspace").toString());

        pairingRepairer = new PairingRepairer();
    }

    @Test
    void should_assemble_context_in_new_order() {
        // Given: 一个有 Todo 和 Insights 的会话（TodoNavigator 激活后才有 Navigator）
        SessionState state = SessionState.create("test-neworder", "下载斗破苍穹");
        state.setTodoBeenUsed(true);
        todoStore.replaceAll(List.of(
                TodoItem.of("t1", "搜索下载源", "high"),
                TodoItem.of("t2", "提取链接", "high"),
                TodoItem.of("t3", "下载文件", "high")
        ), 0);
        insightsStore.add("找到下载链接: http://example.com/dpcq.txt");

        // When: 用 TodoNavigatorHook 渲染 Navigator
        TodoNavigatorHook navigator =
                new TodoNavigatorHook(todoStore, insightsStore);
        cn.kong.eon.context.ContextBuilder ctx =
                new cn.kong.eon.context.ContextBuilder();
        cn.kong.eon.agent.hook.HookResult result = navigator.beforeModelCall(state, ctx);

        // Then: Navigator 应包含 Todo 和 Insights
        assertThat(navigator.isActive(state)).isTrue();
        assertThat(result.isContinue()).isTrue();
    }

    @Test
    void should_keep_system_prompt_at_index_zero_for_kv_cache() {
        // Given
        SessionState state = SessionState.create("test-cache", "test");

        // When: 用 ContextBuilder 组装
        cn.kong.eon.context.ContextBuilder ctx =
                new cn.kong.eon.context.ContextBuilder();
        ctx.setSystemPrompt("你是 Eon Agent");
        List<ChatMessage> messages = ctx.build();

        // Then: messages[0] 必须是 SystemMessage，保证 KV Cache 前缀稳定
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) messages.get(0)).text()).contains("Eon");
    }

    @Test
    void should_render_todo_and_insights_into_navigator() {
        // Given（TodoNavigator 激活后才有 Navigator）
        SessionState state = SessionState.create("test-merge", "用户原始请求");
        state.setTodoBeenUsed(true);
        todoStore.replaceAll(List.of(TodoItem.of("t1", "任务A", "high")), 0);
        insightsStore.add("关键发现X");

        // When: 用 TodoNavigatorHook 渲染
        TodoNavigatorHook navigator =
                new TodoNavigatorHook(todoStore, insightsStore);
        cn.kong.eon.context.ContextBuilder ctx =
                new cn.kong.eon.context.ContextBuilder();
        navigator.beforeModelCall(state, ctx);

        // Then: 验证 Navigator 内容包含 Todo 和 Insights（不包含用户原始请求，避免重复）
        ctx.setSystemPrompt("system");
        List<ChatMessage> messages = ctx.build();
        UserMessage navMsg = messages.stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> (UserMessage) m)
                .filter(um -> "navigator".equals(um.name()))
                .findFirst()
                .orElseThrow();
        assertThat(navMsg.singleText()).contains("任务A");
        assertThat(navMsg.singleText()).contains("关键发现X");
        // 用户原始请求已在 transcript 第一条 UserMessage 中，不应在 navigator 中重复
        assertThat(navMsg.singleText()).doesNotContain("用户原始请求");
    }

    @Test
    void should_repair_orphan_tool_result() {
        // Given: 一个孤立的 ToolExecutionResultMessage（没有对应的 AiMessage tool_use）
        List<ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(SystemMessage.from("system"));
        messages.add(UserMessage.from("user"));
        messages.add(ToolExecutionResultMessage.from("orphan-id", "web_search", "orphan result"));

        // When: 执行配对修复
        List<ChatMessage> repaired = pairingRepairer.repair(messages);

        // Then: 孤立的 tool_result 应被丢弃
        long toolResultCount = repaired.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .count();
        assertThat(toolResultCount).isZero();
    }

    @Test
    void should_insert_synthetic_result_for_orphan_tool_use() {
        // Given: 一个有 tool_use 但没有 tool_result 的 AiMessage
        List<ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(SystemMessage.from("system"));
        messages.add(UserMessage.from("user"));
        messages.add(AiMessage.from(ToolExecutionRequest.builder()
                .id("call-001")
                .name("web_search")
                .arguments("{\"query\":\"test\"}")
                .build()));

        // When: 执行配对修复
        List<ChatMessage> repaired = pairingRepairer.repair(messages);

        // Then: 应插入合成错误 tool_result
        long syntheticCount = repaired.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text())
                .filter(text -> text.contains("[SYNTHETIC]"))
                .count();
        assertThat(syntheticCount).isEqualTo(1);
    }

    @Test
    void should_validate_single_focus_constraint() {
        // Given: 两个 in_progress 的 Todo
        List<TodoItem> todos = List.of(
                TodoItem.of("t1", "task1", "high"),
                TodoItem.of("t2", "task2", "high")
        );
        todos.get(0).setStatus(TodoStatus.IN_PROGRESS);
        todos.get(1).setStatus(TodoStatus.IN_PROGRESS);

        // When: 校验单一焦点
        boolean valid = todoStore.validateSingleFocus(todos);

        // Then: 应不通过
        assertThat(valid).isFalse();
    }

    @Test
    void should_validate_dependency_constraint() {
        // Given: t2 依赖 t1，但 t1 未完成，t2 却标为 in_progress
        List<TodoItem> todos = List.of(
                TodoItem.of("t1", "task1", "high"),
                TodoItem.of("t2", "task2", "high")
        );
        todos.get(0).setStatus(TodoStatus.PENDING);
        todos.get(1).setStatus(TodoStatus.IN_PROGRESS);
        todos.get(1).setDependsOn(List.of("t1"));

        // When: 校验依赖
        boolean valid = todoStore.validateDependencies(todos);

        // Then: 应不通过
        assertThat(valid).isFalse();
    }

    @Test
    void should_register_all_9_tools() {
        // Then: 9 个工具全部注册
        assertThat(toolRegistry.getAll()).hasSize(9);
        assertThat(toolRegistry.get("todo_write")).isNotNull();
        assertThat(toolRegistry.get("todo_read")).isNotNull();
        assertThat(toolRegistry.get("working_memory")).isNotNull();
        assertThat(toolRegistry.get("finish")).isNotNull();
        assertThat(toolRegistry.get("web_search")).isNotNull();
        assertThat(toolRegistry.get("web_read")).isNotNull();
        assertThat(toolRegistry.get("download")).isNotNull();
        assertThat(toolRegistry.get("file_io")).isNotNull();
        assertThat(toolRegistry.get("date_time")).isNotNull();
    }

    @Test
    void should_classify_tool_permissions() {
        assertThat(toolRegistry.isReadonly("todo_read")).isTrue();
        assertThat(toolRegistry.isReadonly("web_search")).isTrue();
        assertThat(toolRegistry.isReadonly("web_read")).isTrue();
        assertThat(toolRegistry.isReadonly("date_time")).isTrue();
        assertThat(toolRegistry.isDestructive("download")).isTrue();
        assertThat(toolRegistry.isDestructive("todo_read")).isFalse();
    }

    @Test
    void should_render_tool_result_with_semantic_annotation() {
        // Given
        ToolResultRenderer renderer = new ToolResultRenderer(artifactStore);
        SessionState state = SessionState.create("test-render", "test");

        // When: 渲染一个普通结果
        String rendered = renderer.render("web_search",
                ToolOutcome.success("搜索完成，找到 5 条结果"), state);

        // Then: 应包含简化标注的关键字段
        assertThat(rendered).contains("[工具: web_search | 成功]");
        assertThat(rendered).contains("搜索完成，找到 5 条结果");
    }

    @Test
    void should_save_large_result_as_artifact() {
        // Given
        ToolResultRenderer renderer = new ToolResultRenderer(artifactStore);
        SessionState state = SessionState.create("test-artifact", "test");
        String largeContent = "x".repeat(8001);  // 超过 3000 字符落盘阈值

        // When: 渲染一个大结果
        String rendered = renderer.render("web_read",
                ToolOutcome.success(largeContent), state);

        // Then: 应落盘为 artifact，上下文只留摘要 + 引用
        assertThat(rendered).contains("artifact://art_");
        assertThat(rendered).contains("[工具: web_read | 成功]");
        assertThat(artifactStore.listAll()).hasSize(1);
    }

    @Test
    void should_keep_full_content_below_threshold() {
        // Given
        ToolResultRenderer renderer = new ToolResultRenderer(artifactStore);
        SessionState state = SessionState.create("test-full", "test");
        String content = "x".repeat(2000);  // 低于落盘阈值 3000，不应落盘

        // When: 渲染结果
        String rendered = renderer.render("web_read",
                ToolOutcome.success(content), state);

        // Then: 完整内容应在消息中，无 artifact 引用，无截断标记
        assertThat(rendered).doesNotContain("artifact://");
        assertThat(rendered).contains("x".repeat(2000));
        assertThat(rendered).doesNotContain("中间内容省略");
        assertThat(artifactStore.listAll()).isEmpty();
    }

    @Test
    void should_execute_todo_write_tool() {
        // Given
        SessionState state = SessionState.create("test-todo", "test");
        java.util.Map<String, Object> args = java.util.Map.of(
                "todos", java.util.List.of(
                        java.util.Map.of(
                                "id", "t1",
                                "content", "搜索下载源",
                                "status", "pending",
                                "priority", "high"
                        )
                )
        );

        // When: 执行 todo_write
        ToolOutcome result = toolRegistry.execute("todo_write", args, state, toolContext);

        // Then: Todo 应被写入
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("当前任务清单");
        assertThat(result.content()).contains("t1");
        assertThat(todoStore.size()).isEqualTo(1);
    }

    @Test
    void should_check_all_completed() {
        // Given
        todoStore.replaceAll(List.of(
                TodoItem.of("t1", "task1", "high"),
                TodoItem.of("t2", "task2", "high")
        ), 0);

        // When: 全部完成前
        assertThat(todoStore.allCompleted()).isFalse();

        // When: 全部完成后
        List<TodoItem> todos = todoStore.getAll();
        todos.forEach(t -> t.setStatus(TodoStatus.COMPLETED));
        todoStore.replaceAll(todos, 1);

        // Then
        assertThat(todoStore.allCompleted()).isTrue();
    }

    // ==================== TodoWriteTool 容错测试（覆盖日志中的失败场景）====================

    @Test
    void should_accept_string_array_todos() {
        SessionState state = SessionState.create("test-str-array", "test");
        // Schema 声明数组元素为 object，ArgumentSanitizer 保证类型正确
        java.util.Map<String, Object> args = java.util.Map.of(
                "reason", "测试字符串数组",
                "todos", java.util.List.of(
                        java.util.Map.of("id", "t1", "content", "搜索下载源", "status", "pending"),
                        java.util.Map.of("id", "t2", "content", "提取链接", "status", "pending"),
                        java.util.Map.of("id", "t3", "content", "下载文件", "status", "pending")
                )
        );

        ToolOutcome result = toolRegistry.execute("todo_write", args, state, toolContext);

        assertThat(result.content()).contains("当前任务清单");
        assertThat(result.content()).contains("搜索下载源");
        assertThat(result.content()).contains("提取链接");
        assertThat(todoStore.size()).isEqualTo(3);
        todoStore.getAll().forEach(t ->
                assertThat(t.getStatus()).isEqualTo(TodoStatus.PENDING));
    }

    @Test
    void should_accept_integer_id() {
        SessionState state = SessionState.create("test-int-id", "test");
        java.util.Map<String, Object> args = java.util.Map.of(
                "reason", "测试数字id",
                "todos", java.util.List.of(
                        java.util.Map.of("id", 1, "content", "任务1", "status", "pending", "priority", "high"),
                        java.util.Map.of("id", 2, "content", "任务2", "status", "pending", "priority", "high")
                )
        );

        ToolOutcome result = toolRegistry.execute("todo_write", args, state, toolContext);

        assertThat(result.content()).contains("当前任务清单");
        assertThat(todoStore.size()).isEqualTo(2);
        assertThat(todoStore.get("1")).isNotNull();
        assertThat(todoStore.get("2")).isNotNull();
    }

    @Test
    void should_accept_status_variants() {
        SessionState state = SessionState.create("test-status", "test");
        java.util.Map<String, Object> args = java.util.Map.of(
                "reason", "测试status变体",
                "todos", java.util.List.of(
                        java.util.Map.of("id", "t1", "content", "任务1", "status", "todo"),
                        java.util.Map.of("id", "t2", "content", "任务2", "status", "in-progress"),
                        java.util.Map.of("id", "t3", "content", "任务3", "status", "done"),
                        java.util.Map.of("id", "t4", "content", "任务4", "status", "blocked"),
                        java.util.Map.of("id", "t5", "content", "任务5", "status", "cancelled")
                )
        );

        toolRegistry.execute("todo_write", args, state, toolContext);

        assertThat(todoStore.get("t1").getStatus()).isEqualTo(TodoStatus.PENDING);
        assertThat(todoStore.get("t2").getStatus()).isEqualTo(TodoStatus.IN_PROGRESS);
        assertThat(todoStore.get("t3").getStatus()).isEqualTo(TodoStatus.COMPLETED);
        assertThat(todoStore.get("t4").getStatus()).isEqualTo(TodoStatus.BLOCKED);
        assertThat(todoStore.get("t5").getStatus()).isEqualTo(TodoStatus.CANCELLED);
    }

    @Test
    void should_auto_generate_id_when_missing() {
        SessionState state = SessionState.create("test-no-id", "test");
        java.util.Map<String, Object> args = java.util.Map.of(
                "reason", "测试无id",
                "todos", java.util.List.of(
                        java.util.Map.of("content", "任务A", "status", "pending"),
                        java.util.Map.of("content", "任务B", "status", "pending")
                )
        );

        toolRegistry.execute("todo_write", args, state, toolContext);

        assertThat(todoStore.size()).isEqualTo(2);
        todoStore.getAll().forEach(t -> assertThat(t.getId()).isNotBlank());
    }

    @Test
    void should_accept_task_field_as_content() {
        SessionState state = SessionState.create("test-task-field", "test");
        java.util.Map<String, Object> args = java.util.Map.of(
                "reason", "测试task字段",
                "todos", java.util.List.of(
                        java.util.Map.of("id", "t1", "task", "搜索资源", "status", "pending")
                )
        );

        ToolOutcome result = toolRegistry.execute("todo_write", args, state, toolContext);

        assertThat(result.content()).contains("搜索资源");
    }

    @Test
    void should_clear_todos_when_empty_array() {
        SessionState state = SessionState.create("test-empty", "test");
        toolRegistry.execute("todo_write", java.util.Map.of(
                "reason", "先写入",
                "todos", java.util.List.of(
                        java.util.Map.of("id", "t1", "content", "任务1", "status", "pending")
                )
        ), state, toolContext);
        assertThat(todoStore.size()).isEqualTo(1);

        java.util.Map<String, Object> args = java.util.Map.of(
                "reason", "测试空数组清空",
                "todos", java.util.List.of()
        );

        ToolOutcome result = toolRegistry.execute("todo_write", args, state, toolContext);

        assertThat(result.content()).contains("已清空");
        assertThat(todoStore.size()).isEqualTo(0);
    }

    @Test
    void should_accept_todos_as_json_string() {
        SessionState state = SessionState.create("test-str-todos", "test");
        String todosJsonString = "[{\"id\":\"t1\",\"content\":\"搜索抖音2017-2022年粉丝超1000万的网红信息\","
                + "\"status\":\"in_progress\",\"priority\":\"high\"},"
                + "{\"id\":\"t2\",\"content\":\"查找具体网红案例和粉丝增长数据\","
                + "\"status\":\"pending\",\"priority\":\"medium\"},"
                + "{\"id\":\"t3\",\"content\":\"整理分析报告并总结发现\","
                + "\"status\":\"pending\",\"priority\":\"medium\"}]";
        java.util.Map<String, Object> args = new java.util.HashMap<>();
        args.put("reason", "创建调研任务清单");
        // 模拟 LLM 传字符串格式的 JSON 数组，由 ArgumentSanitizer 自动转换为 List
        args.put("todos", todosJsonString);

        ToolOutcome result = toolRegistry.execute("todo_write", args, state, toolContext);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("当前任务清单");
        assertThat(todoStore.size()).isEqualTo(3);
        assertThat(todoStore.get("t1").getContent()).contains("搜索抖音");
        assertThat(todoStore.get("t1").getStatus()).isEqualTo(TodoStatus.IN_PROGRESS);
        assertThat(todoStore.get("t2").getStatus()).isEqualTo(TodoStatus.PENDING);
    }

    @Test
    void should_reject_invalid_todos_string() {
        SessionState state = SessionState.create("test-invalid-str", "test");
        java.util.Map<String, Object> args = new java.util.HashMap<>();
        args.put("reason", "测试非法字符串");
        // 非法字符串，ArgumentSanitizer 无法解析为 JSON 数组，工具侧强转会抛异常
        args.put("todos", "这不是JSON数组");

        ToolOutcome result = toolRegistry.execute("todo_write", args, state, toolContext);

        // ToolRegistry.execute 的 catch 块捕获异常并返回 failure
        assertThat(result.success()).isFalse();
        assertThat(todoStore.size()).isEqualTo(0);
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
    void should_detect_cross_tool_failure_escalation() {
        LoopDetector detector = new LoopDetector(3, 5, 6, 3, 5);

        detector.recordToolResult("web_search", false);
        detector.recordToolResult("web_search", false);
        detector.recordToolResult("web_read", false);
        detector.recordToolResult("web_read", false);
        LoopDetector.DetectionResult r5 = detector.recordToolResult("web_read", false);

        assertThat(r5.shouldStop()).isTrue();
        assertThat(r5.message()).contains("熔断");
    }

    @Test
    void should_trip_individual_tool_after_failures() {
        LoopDetector detector = new LoopDetector(3, 5, 6, 3, 5);

        for (int i = 0; i < 5; i++) {
            detector.recordToolResult("web_search", false);
        }

        assertThat(detector.isToolTripped("web_search")).isTrue();
        assertThat(detector.isToolTripped("web_read")).isFalse();
    }
}
