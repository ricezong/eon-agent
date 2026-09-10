package cn.kong.eon.store;

import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.pipeline.ContextPipeline;
import cn.kong.eon.agent.context.block.ContextBlock;
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
 * JSONL 消息存储。磁盘 append-only 账本（永不修改）+ 内存 ContextWindow 上下文视图（可改写）。
 * 消息序号由本类发放（唯一知道账本长度的地方），回放与常规入站共用序号。
 */
public class JsonlStore {
    private static final Logger log = LoggerFactory.getLogger(JsonlStore.class);

    private final Path jsonlFile;
    private final ObjectMapper mapper;
    private final ContextWindow window = new ContextWindow();
    private final ContextPipeline pipeline;
    /** 下一条消息的序号，等于账本当前行数 */
    private int messageCount = 0;

    public JsonlStore(Path jsonlFile, ContextPipeline pipeline, int replayFrom, ObjectMapper objectMapper) {
        this.jsonlFile = jsonlFile;
        this.mapper = objectMapper;
        this.pipeline = pipeline;
        try {
            Files.createDirectories(jsonlFile.getParent());
            if (Files.exists(jsonlFile)) {
                loadAll(replayFrom);
            }
        } catch (IOException e) {
            throw new RuntimeException("JSONL 存储初始化失败: " + jsonlFile, e);
        }
    }

    /**
     * 追加消息：入站管线处置 → 内存窗口 → 磁盘账本。
     */
    public synchronized void append(ChatMessage message, Set<String> succeededToolCalls) {
        List<ContextBlock> blocks = pipeline.ingest(message, succeededToolCalls, messageCount);
        window.addAll(blocks);
        appendToLedger(message, succeededToolCalls);
        messageCount++;
    }

    /** 无工具上下文时的简化重载。 */
    public synchronized void append(ChatMessage message) {
        append(message, Collections.emptySet());
    }

    /** 获取内存窗口。 */
    public ContextWindow window() {
        return window;
    }

    /** 追加 JSON 到磁盘账本。 */
    private void appendToLedger(ChatMessage message, Set<String> succeededToolCalls) {
        try {
            SerializedMessage sm = SerializedMessage.from(message);
            if (message instanceof ToolExecutionResultMessage m) {
                sm.success = succeededToolCalls.contains(m.id());
            }
            Files.writeString(jsonlFile, mapper.writeValueAsString(sm) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("JSONL 追加失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 从磁盘账本回放消息到内存窗口，走与常规入站相同的管线。
     * 水位线之前的消息已进摘要，不再回放。
     */
    private void loadAll(int fromSeq) {
        try {
            List<String> lines = Files.readAllLines(jsonlFile);
            int replayed = 0;
            for (int i = fromSeq; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                SerializedMessage sm;
                ChatMessage msg;
                try {
                    sm = mapper.readValue(line, SerializedMessage.class);
                    msg = sm.toChatMessage();
                } catch (Exception e) {
                    log.error("反序列化失败，跳过第 {} 行: {}", i, e.getMessage());
                    continue;
                }
                Set<String> succeeded = Boolean.TRUE.equals(sm.success) && sm.toolCallId != null
                        ? Set.of(sm.toolCallId)
                        : Set.of();
                window.addAll(pipeline.ingest(msg, succeeded, i));
                replayed++;
            }
            messageCount = lines.size();
            if (replayed > 0) {
                log.info("从 JSONL 回放消息 #{}~{} → {} 个内容块", fromSeq, lines.size(), window.size());
            }
        } catch (IOException e) {
            log.error("JSONL 加载失败: {}", e.getMessage(), e);
        }
    }

    /** JSONL 序列化中间结构。 */
    public static class SerializedMessage {
        public String type;       // 消息类型：system/user/ai/tool
        public String content;
        public String name;           // UserMessage 的 name 属性
        public String toolCallId;
        public String toolName;
        /** 工具结果是否执行成功（仅 tool 行），回放时据此还原格式化中的执行状态 */
        public Boolean success;
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
