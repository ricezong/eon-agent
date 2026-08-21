package cn.kong.eon.store;

import cn.kong.eon.util.JsonMapper;
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
 * Append-Only JSONL 消息存储。原始账本，永不修改，用于审计和回滚。
 * 每条消息一行 JSON。
 */
public class JsonlStore {
    private static final Logger log = LoggerFactory.getLogger(JsonlStore.class);

    private final Path jsonlFile;
    private final ObjectMapper mapper;
    private final List<ChatMessage> messages = new ArrayList<>();

    public JsonlStore(Path jsonlFile) {
        this.jsonlFile = jsonlFile;
        this.mapper = JsonMapper.get();
        try {
            Files.createDirectories(jsonlFile.getParent());
            if (Files.exists(jsonlFile)) {
                loadAll();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to init JSONL store: " + jsonlFile, e);
        }
    }

    /** 追加一条消息（永不修改已有消息）。 */
    public synchronized void append(ChatMessage message) {
        messages.add(message);
        String json = serialize(message);
        try {
            Files.writeString(jsonlFile, json + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to append JSONL: {}", e.getMessage(), e);
        }
    }

    /** 返回消息快照（浅拷贝，修改不影响原始账本）。 */
    public synchronized List<ChatMessage> snapshot() {
        return new ArrayList<>(messages);
    }

    public synchronized int size() { return messages.size(); }

    public synchronized void clear() {
        messages.clear();
        try {
            Files.deleteIfExists(jsonlFile);
        } catch (IOException e) {
            log.warn("Failed to delete JSONL file: {}", e.getMessage());
        }
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
            log.info("Loaded {} messages from JSONL", messages.size());
        } catch (IOException e) {
            log.error("Failed to load JSONL: {}", e.getMessage(), e);
        }
    }

    private String serialize(ChatMessage message) {
        try {
            SerializedMessage sm = SerializedMessage.from(message);
            return mapper.writeValueAsString(sm);
        } catch (Exception e) {
            log.error("Serialize failed: {}", e.getMessage(), e);
            return "{}";
        }
    }

    private ChatMessage deserialize(String json) {
        try {
            SerializedMessage sm = mapper.readValue(json, SerializedMessage.class);
            return sm.toChatMessage();
        } catch (Exception e) {
            log.error("Deserialize failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /** JSONL 序列化中间结构。 */
    public static class SerializedMessage {
        public String type;       // system / user / ai / tool
        public String content;
        public String name;           // UserMessage 的 name 属性（如 tool_catalog, navigator）
        public String toolCallId;
        public String toolName;
        public List<ToolCallRef> toolCalls;

        public SerializedMessage() {}

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
                case "tool" -> ToolExecutionResultMessage.from(toolCallId, toolName != null ? toolName : "unknown", content != null ? content : "");
                default -> throw new IllegalStateException("Unknown message type: " + type);
            };
        }
    }

    public static class ToolCallRef {
        public String id;
        public String name;
        public String arguments;
    }
}
