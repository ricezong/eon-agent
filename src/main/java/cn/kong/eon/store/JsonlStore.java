package cn.kong.eon.store;

import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.block.BlockProjector;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.agent.context.pipeline.ContextPipeline;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * JSONL 消息存储。维护两层结构：磁盘 append-only 审计账本（永不修改）
 * 和内存 {@link ContextWindow} 上下文视图（可被入站管线与压缩策略改写）。
 * 磁盘记录入站处置后的形态，与内存窗口保持一致。
 */
public class JsonlStore {
    private static final Logger log = LoggerFactory.getLogger(JsonlStore.class);

    private final Path jsonlFile;
    private final ObjectMapper mapper;
    private final ContextWindow window = new ContextWindow();
    private final ContextPipeline pipeline;

    public JsonlStore(Path jsonlFile, ObjectMapper objectMapper, ContextPipeline pipeline) {
        this.jsonlFile = jsonlFile;
        this.mapper = objectMapper;
        this.pipeline = pipeline;
        try {
            Files.createDirectories(jsonlFile.getParent());
            if (Files.exists(jsonlFile)) {
                loadAll();
            }
        } catch (IOException e) {
            throw new RuntimeException("JSONL 存储初始化失败: " + jsonlFile, e);
        }
    }

    /**
     * 追加一条消息：经入站管线处置 → 进入内存窗口 → 写磁盘账本。
     *
     * @param turn               入站轮次
     * @param succeededToolCalls 本轮执行成功的工具调用 id（可恢复性的判定依据）
     */
    public synchronized void append(ChatMessage message, int turn, Set<String> succeededToolCalls) {
        List<ContextBlock> blocks = pipeline.ingest(message, turn, succeededToolCalls);
        window.addAll(blocks);
        appendToLedger(message);
    }

    /** 无工具上下文时的简化重载。 */
    public synchronized void append(ChatMessage message) {
        append(message, 0, Collections.emptySet());
    }

    /** 获取内存窗口，压缩策略与度量直接作用于它。 */
    public ContextWindow window() {
        return window;
    }

    /** 追加一条 JSON 到磁盘账本。 */
    private void appendToLedger(ChatMessage message) {
        String json = serialize(message);
        try {
            Files.writeString(jsonlFile, json + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("JSONL 追加失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 从磁盘账本加载历史消息到内存窗口，历史消息原样恢复不回溯入站处置。
     * 轮次按用户消息计数恢复：一条用户消息标志新一轮开始，与运行期的轮次定义一致。
     */
    private void loadAll() {
        try {
            List<String> lines = Files.readAllLines(jsonlFile);
            int turn = 0;
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                ChatMessage msg = deserialize(line);
                if (msg == null) {
                    continue;
                }
                if (msg instanceof UserMessage) {
                    turn++;
                }
                // 历史消息原样恢复：入站处置不回溯
                window.addAll(BlockProjector.explode(msg, "h" + window.size(), turn));
            }
            if (!lines.isEmpty()) {
                log.info("从 JSONL 加载 {} 条消息 → {} 个内容块", lines.size(), window.size());
            }
        } catch (IOException e) {
            log.error("JSONL 加载失败: {}", e.getMessage(), e);
        }
    }

    /** 序列化消息为 JSON 字符串。 */
    private String serialize(ChatMessage message) {
        try {
            SerializedMessage sm = SerializedMessage.from(message);
            return mapper.writeValueAsString(sm);
        } catch (Exception e) {
            log.error("序列化失败: {}", e.getMessage(), e);
            return "{}";
        }
    }

    /** 反序列化 JSON 字符串为消息。 */
    private ChatMessage deserialize(String json) {
        try {
            SerializedMessage sm = mapper.readValue(json, SerializedMessage.class);
            return sm.toChatMessage();
        } catch (Exception e) {
            log.error("反序列化失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * JSONL 序列化中间结构。
     */
    public static class SerializedMessage {
        public String type;       // 消息类型：system/user/ai/tool
        public String content;
        public String name;           // UserMessage 的 name 属性
        public String toolCallId;
        public String toolName;
        public List<ToolCallRef> toolCalls;

        public SerializedMessage() {
        }

        /** 从 ChatMessage 构建序列化结构。 */
        public static SerializedMessage from(ChatMessage msg) {
            SerializedMessage sm = new SerializedMessage();
            if (msg instanceof SystemMessage m) {
                sm.type = "system";
                sm.content = m.text();
            } else if (msg instanceof UserMessage m) {
                sm.type = "user";
                sm.content = m.singleText();
                sm.name = m.name();
            } else if (msg instanceof AiMessage m) {
                sm.type = "ai";
                sm.content = m.text();
                if (m.hasToolExecutionRequests()) {
                    sm.toolCalls = new ArrayList<>();
                    for (var ter : m.toolExecutionRequests()) {
                        ToolCallRef ref = new ToolCallRef();
                        ref.id = ter.id();
                        ref.name = ter.name();
                        ref.arguments = ter.arguments();
                        sm.toolCalls.add(ref);
                    }
                }
            } else if (msg instanceof ToolExecutionResultMessage m) {
                sm.type = "tool";
                sm.toolCallId = m.id();
                sm.toolName = m.toolName();
                sm.content = m.text();
            }
            return sm;
        }

        /** 从序列化结构重建 ChatMessage。 */
        public ChatMessage toChatMessage() {
            return switch (type) {
                case "system" -> SystemMessage.from(content);
                case "user" -> {
                    UserMessage um = name != null
                            ? UserMessage.from(name, content != null ? content : "")
                            : UserMessage.from(content != null ? content : "");
                    yield um;
                }
                case "ai" -> {
                    AiMessage ai;
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        List<ToolExecutionRequest> requests = new ArrayList<>();
                        for (ToolCallRef ref : toolCalls) {
                            requests.add(ToolExecutionRequest.builder()
                                    .id(ref.id)
                                    .name(ref.name)
                                    .arguments(ref.arguments)
                                    .build());
                        }
                        ai = content != null
                                ? AiMessage.from(content, requests)
                                : AiMessage.from(requests);
                    } else {
                        ai = AiMessage.from(content != null ? content : "");
                    }
                    yield ai;
                }
                case "tool" ->
                        ToolExecutionResultMessage.from(toolCallId, toolName != null ? toolName : "unknown", content != null ? content : "");
                default -> throw new IllegalStateException("未知消息类型: " + type);
            };
        }
    }

    /** 工具调用引用，用于序列化 AI 消息中的工具执行请求。 */
    public static class ToolCallRef {
        public String id;
        public String name;
        public String arguments;
    }
}
