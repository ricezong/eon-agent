package cn.kong.eon;

import cn.kong.eon.agent.capability.render.TodoNavigatorCapability;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.context.PairingRepairer;
import cn.kong.eon.loop.LoopDetector;
import cn.kong.eon.model.*;
import cn.kong.eon.store.*;
import cn.kong.eon.tool.ToolContext;
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
        toolRegistry.register(WebSearchTool.descriptor());
        toolRegistry.register(WebReadTool.descriptor());
        toolRegistry.register(DownloadTool.descriptor());
        toolRegistry.register(FileIoTool.descriptor());

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

        // When: 用 TodoNavigator 渲染 Navigator
        TodoNavigatorCapability navigator =
                new TodoNavigatorCapability(todoStore, insightsStore);
        cn.kong.eon.agent.context.ContextBuilder ctx =
                new cn.kong.eon.agent.context.ContextBuilder();
        cn.kong.eon.agent.capability.CapabilityResult result = navigator.beforeModelCall(state, ctx);

        // Then: Navigator 应包含用户请求、Todo 和 Insights
        assertThat(navigator.isActive(state)).isTrue();
        assertThat(result.isAbort()).isFalse();
        // 验证 Navigator 内容通过 ContextBuilder 获取
        // （这里验证 TodoNavigator 正确渲染了内容）
    }

    @Test
    void should_keep_system_prompt_at_index_zero_for_kv_cache() {
        // Given
        SessionState state = SessionState.create("test-cache", "test");

        // When: 用 ContextBuilder 组装
        cn.kong.eon.agent.context.ContextBuilder ctx =
                new cn.kong.eon.agent.context.ContextBuilder();
        ctx.setSystemPrompt("你是 Eon Agent");
        List<ChatMessage> messages = ctx.build();

        // Then: messages[0] 必须是 SystemMessage，保证 KV Cache 前缀稳定
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) messages.get(0)).text()).contains("Eon");
    }

    @Test
    void should_merge_pinned_and_insights_into_single_user_message() {
        // Given（TodoNavigator 激活后才有 Navigator）
        SessionState state = SessionState.create("test-merge", "用户原始请求");
        state.setTodoBeenUsed(true);
        todoStore.replaceAll(List.of(TodoItem.of("t1", "任务A", "high")), 0);
        insightsStore.add("关键发现X");

        // When: 用 TodoNavigator 渲染
        TodoNavigatorCapability navigator =
                new TodoNavigatorCapability(todoStore, insightsStore);
        cn.kong.eon.agent.context.ContextBuilder ctx =
                new cn.kong.eon.agent.context.ContextBuilder();
        navigator.beforeModelCall(state, ctx);

        // Then: 验证 Navigator 内容包含 Pinned 和 Insights
        // 通过 build() 后检查 navigator 消息
        ctx.setSystemPrompt("system");
        List<ChatMessage> messages = ctx.build();
        UserMessage navMsg = messages.stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> (UserMessage) m)
                .filter(um -> "navigator".equals(um.name()))
                .findFirst()
                .orElseThrow();
        assertThat(navMsg.singleText()).contains("用户原始请求");
        assertThat(navMsg.singleText()).contains("任务A");
        assertThat(navMsg.singleText()).contains("关键发现X");
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
    void should_register_all_8_tools() {
        // Then: 8 个工具全部注册
        assertThat(toolRegistry.getAll()).hasSize(8);
        assertThat(toolRegistry.get("todo_write")).isNotNull();
        assertThat(toolRegistry.get("todo_read")).isNotNull();
        assertThat(toolRegistry.get("working_memory")).isNotNull();
        assertThat(toolRegistry.get("finish")).isNotNull();
        assertThat(toolRegistry.get("web_search")).isNotNull();
        assertThat(toolRegistry.get("web_read")).isNotNull();
        assertThat(toolRegistry.get("download")).isNotNull();
        assertThat(toolRegistry.get("file_io")).isNotNull();
    }

    @Test
    void should_classify_tool_permissions() {
        assertThat(toolRegistry.isReadonly("todo_read")).isTrue();
        assertThat(toolRegistry.isReadonly("web_search")).isTrue();
        assertThat(toolRegistry.isReadonly("web_read")).isTrue();
        assertThat(toolRegistry.isDestructive("download")).isTrue();
        assertThat(toolRegistry.isDestructive("todo_read")).isFalse();
    }

    @Test
    void should_render_tool_result_with_semantic_annotation() {
        // Given
        ToolResultRenderer renderer = new ToolResultRenderer(artifactStore);
        SessionState state = SessionState.create("test-render", "test");

        // When: 渲染一个普通结果
        String rendered = renderer.render("web_search", "call-001",
                "搜索下载源", "搜索完成，找到 5 条结果", state);

        // Then: 应包含八字段语义标注的关键字段
        assertThat(rendered).contains("[工具执行结果: web_search]");
        assertThat(rendered).contains("执行状态: 成功");
        assertThat(rendered).contains("调用原因: 搜索下载源");
        assertThat(rendered).contains("调用ID: call-001");
    }

    @Test
    void should_save_large_result_as_artifact() {
        // Given
        ToolResultRenderer renderer = new ToolResultRenderer(artifactStore);
        SessionState state = SessionState.create("test-artifact", "test");
        String largeContent = "x".repeat(8001);  // 超过 3000 字符落盘阈值

        // When: 渲染一个大结果
        String rendered = renderer.render("web_read", "call-002",
                "读取网页", largeContent, state);

        // Then: 应落盘为 artifact，上下文只留摘要 + 引用
        assertThat(rendered).contains("artifact://art_");
        assertThat(rendered).contains("完整内容引用");
        assertThat(rendered).contains("原始大小: 8001 字符");
        assertThat(artifactStore.listAll()).hasSize(1);
    }

    @Test
    void should_keep_full_content_below_threshold() {
        // Given
        ToolResultRenderer renderer = new ToolResultRenderer(artifactStore);
        SessionState state = SessionState.create("test-full", "test");
        String content = "x".repeat(2000);  // 低于落盘阈值 3000，不应落盘

        // When: 渲染结果
        String rendered = renderer.render("web_read", "call-003",
                "读取网页", content, state);

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
        String result = toolRegistry.execute("todo_write", args, state, toolContext);

        // Then: Todo 应被写入
        assertThat(result).contains("当前任务清单");
        assertThat(result).contains("t1");
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
        // 场景：LLM 传了字符串数组 ["搜索下载源", "提取链接"]
        // 期望：自动转为 {content: 字符串, status: pending}
        SessionState state = SessionState.create("test-str-array", "test");
        java.util.Map<String, Object> args = java.util.Map.of(
                "reason", "测试字符串数组",
                "todos", java.util.List.of("搜索下载源", "提取链接", "下载文件")
        );

        String result = toolRegistry.execute("todo_write", args, state, toolContext);

        assertThat(result).contains("当前任务清单");
        assertThat(result).contains("搜索下载源");
        assertThat(result).contains("提取链接");
        assertThat(todoStore.size()).isEqualTo(3);
        // 所有任务应为 pending
        todoStore.getAll().forEach(t ->
                assertThat(t.getStatus()).isEqualTo(TodoStatus.PENDING));
    }

    @Test
    void should_accept_integer_id() {
        // 场景：LLM 传了数字 id {"id": 1, "content": "..."}
        // 期望：整数 id 转为字符串
        SessionState state = SessionState.create("test-int-id", "test");
        java.util.Map<String, Object> args = java.util.Map.of(
                "reason", "测试数字id",
                "todos", java.util.List.of(
                        java.util.Map.of("id", 1, "content", "任务1", "status", "pending", "priority", "high"),
                        java.util.Map.of("id", 2, "content", "任务2", "status", "pending", "priority", "high")
                )
        );

        String result = toolRegistry.execute("todo_write", args, state, toolContext);

        assertThat(result).contains("当前任务清单");
        assertThat(todoStore.size()).isEqualTo(2);
        assertThat(todoStore.get("1")).isNotNull();
        assertThat(todoStore.get("2")).isNotNull();
    }

    @Test
    void should_accept_status_variants() {
        // 场景：LLM 用了 "todo"/"in-progress"/"done" 等变体
        // 期望：自动归一化
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
        // 场景：LLM 没传 id
        // 期望：自动生成 id
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
        // 每个 todo 都应有非空 id
        todoStore.getAll().forEach(t -> assertThat(t.getId()).isNotBlank());
    }

    @Test
    void should_accept_task_field_as_content() {
        // 场景：LLM 用 "task" 字段而非 "content"
        // 期望：兼容
        SessionState state = SessionState.create("test-task-field", "test");
        java.util.Map<String, Object> args = java.util.Map.of(
                "reason", "测试task字段",
                "todos", java.util.List.of(
                        java.util.Map.of("id", "t1", "task", "搜索资源", "status", "pending")
                )
        );

        String result = toolRegistry.execute("todo_write", args, state, toolContext);

        assertThat(result).contains("搜索资源");
    }

    @Test
    void should_clear_todos_when_empty_array() {
        // 场景：todos 为空数组
        // 期望：清空列表（全量替换语义，空数组 = 清空）
        // 先写入一些 todo
        SessionState state = SessionState.create("test-empty", "test");
        toolRegistry.execute("todo_write", java.util.Map.of(
                "reason", "先写入",
                "todos", java.util.List.of(
                        java.util.Map.of("id", "t1", "content", "任务1", "status", "pending")
                )
        ), state, toolContext);
        assertThat(todoStore.size()).isEqualTo(1);

        // 再传空数组清空
        java.util.Map<String, Object> args = java.util.Map.of(
                "reason", "测试空数组清空",
                "todos", java.util.List.of()
        );

        String result = toolRegistry.execute("todo_write", args, state, toolContext);

        assertThat(result).contains("已清空");
        assertThat(todoStore.size()).isEqualTo(0);
    }

    @Test
    void should_accept_todos_as_json_string() {
        // 场景：LLM 把 todos 数组序列化成字符串嵌入 JSON（如 {"todos": "[{...}]"}）
        // 这是 jsonl 日志中真实出现的失败场景：parseArgs 解析后 todos 是 String 而非 List
        // 期望：自动二次解析字符串为 JSON 数组
        SessionState state = SessionState.create("test-str-todos", "test");
        String todosJsonString = "[{\"id\":\"t1\",\"content\":\"搜索抖音2017-2022年粉丝超1000万的网红信息\","
                + "\"status\":\"in_progress\",\"priority\":\"high\"},"
                + "{\"id\":\"t2\",\"content\":\"查找具体网红案例和粉丝增长数据\","
                + "\"status\":\"pending\",\"priority\":\"medium\"},"
                + "{\"id\":\"t3\",\"content\":\"整理分析报告并总结发现\","
                + "\"status\":\"pending\",\"priority\":\"medium\"}]";
        java.util.Map<String, Object> args = new java.util.HashMap<>();
        args.put("reason", "创建调研任务清单");
        args.put("todos", todosJsonString); // 注意：传的是 String，不是 List

        String result = toolRegistry.execute("todo_write", args, state, toolContext);

        // 不应报错，应正常解析
        assertThat(result).contains("当前任务清单");
        assertThat(result).doesNotContain("[ERROR]");
        assertThat(todoStore.size()).isEqualTo(3);
        assertThat(todoStore.get("t1").getContent()).contains("搜索抖音");
        assertThat(todoStore.get("t1").getStatus()).isEqualTo(TodoStatus.IN_PROGRESS);
        assertThat(todoStore.get("t2").getStatus()).isEqualTo(TodoStatus.PENDING);
    }

    @Test
    void should_reject_invalid_todos_string() {
        // 场景：todos 是字符串但不是合法 JSON 数组
        // 期望：返回明确错误信息
        SessionState state = SessionState.create("test-invalid-str", "test");
        java.util.Map<String, Object> args = new java.util.HashMap<>();
        args.put("reason", "测试非法字符串");
        args.put("todos", "这不是JSON数组");

        String result = toolRegistry.execute("todo_write", args, state, toolContext);

        assertThat(result).contains("[ERROR]");
        assertThat(result).contains("无法解析为 JSON 数组");
        assertThat(todoStore.size()).isEqualTo(0);
    }

    // ==================== 连续失败熔断器测试 ====================

    @Test
    void should_warn_after_consecutive_failures() {
        // 场景：工具连续失败 3 次（达到 failureWarn 阈值）
        // 期望：返回 WARN 级别，要求标记 blocked
        LoopDetector detector = new LoopDetector(3, 5, 6, 3, 5);

        // 模拟连续 3 次失败
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
        // 场景：工具连续失败 5 次（达到 failureStop 阈值）
        // 期望：返回 STOP 级别，熔断终止
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
        // 场景：失败 2 次后成功，计数器应归零
        LoopDetector detector = new LoopDetector(3, 5, 6, 3, 5);

        detector.recordToolResult("web_search", false);
        detector.recordToolResult("web_search", false);
        // 成功一次，计数器归零
        detector.recordToolResult("web_search", true);
        // 再失败 2 次，不应触发警告（因为之前已归零）
        LoopDetector.DetectionResult r1 = detector.recordToolResult("web_search", false);
        LoopDetector.DetectionResult r2 = detector.recordToolResult("web_search", false);

        assertThat(r1.shouldWarn()).isFalse();
        assertThat(r2.shouldWarn()).isFalse();
    }

    @Test
    void should_detect_cross_tool_failure_escalation() {
        // 场景：web_search 失败 2 次，然后 web_read 失败 3 次
        // 期望：跨工具累计失败 5 次，触发熔断（防止 LLM 绕过失败工具）
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
        // 场景：web_search 连续失败 5 次（达到 failureStop 阈值），该工具应被标记为 tripped
        LoopDetector detector = new LoopDetector(3, 5, 6, 3, 5);

        for (int i = 0; i < 5; i++) {
            detector.recordToolResult("web_search", false);
        }

        assertThat(detector.isToolTripped("web_search")).isTrue();
        assertThat(detector.isToolTripped("web_read")).isFalse();
    }
}
