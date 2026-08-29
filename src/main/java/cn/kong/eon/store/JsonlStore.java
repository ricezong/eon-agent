package cn.kong.eon.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JSONL 消息存储，两层结构：
 * <ul>
 *   <li>磁盘文件：append-only 审计账本，永不修改，每条消息一行 JSON，供事后检索回溯</li>
 *   <li>内存列表：当前上下文视图，可被压缩流程原地修改（截短/占位/删除），
 *       是每轮构建 LLM 上下文的数据源</li>
 * </ul>
 */
public class JsonlStore {
    private static final Logger log = LoggerFactory.getLogger(JsonlStore.class);

    private final Path jsonlFile;
    private final ObjectMapper mapper;
    private final List<ChatMessage> messages = new ArrayList<>();

    public JsonlStore(Path jsonlFile, ObjectMapper objectMapper) {
        this.jsonlFile = jsonlFile;
        this.mapper = objectMapper;
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
     * 追加一条消息（永不修改已有消息）。
     */
    public synchronized void append(ChatMessage message) {
        messages.add(message);
        String json = serialize(message);
        try {
            Files.writeString(jsonlFile, json + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("JSONL 追加失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 返回消息快照（浅拷贝，修改不影响内部视图）。
     */
    public synchronized List<ChatMessage> snapshot() {
        return new ArrayList<>(messages);
    }

    /**
     * 用压缩后的消息列表整体替换内存视图。
     * <p>
     * 只影响内存视图，不回写磁盘——磁盘账本保持完整历史，
     * 供摘要提示词中约定的检索回溯使用。
     */
    public synchronized void replaceAll(List<ChatMessage> compressed) {
        messages.clear();
        messages.addAll(compressed);
        log.debug("上下文视图已更新: {} 条消息", messages.size());
    }

    private void loadAll() {
        try {
            List<String> lines = Files.readAllLines(jsonlFile);
            for (String line : lines) {
                if (line.isBlank()) continue;
                ChatMessage msg = deserialize(line);
                if (msg != null) {
                    messages.add(msg);
                }
            }
            log.info("从 JSONL 加载 {} 条消息", messages.size());
        } catch (IOException e) {
            log.error("JSONL 加载失败: {}", e.getMessage(), e);
        }
    }

    private String serialize(ChatMessage message) {
        try {
            SerializedMessage sm = SerializedMessage.from(message);
            return mapper.writeValueAsString(sm);
        } catch (Exception e) {
            log.error("序列化失败: {}", e.getMessage(), e);
            return "{}";
        }
    }

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

    public static class ToolCallRef {
        public String id;
        public String name;
        public String arguments;
    }
}
