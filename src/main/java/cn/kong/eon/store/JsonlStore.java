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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * JSONL 消息存储，两层结构：
 * <ul>
 *   <li><b>磁盘文件</b>：append-only 审计账本，永不修改，每条消息一行 JSON，供事后检索回溯</li>
 *   <li><b>内存窗口</b>：{@link ContextWindow}，当前上下文视图。以<b>内容块</b>为单位，
 *       可被入站管线与压缩策略就地改写（卸载/截短/占位/删除），
 *       是每轮构建 LLM 上下文的数据源</li>
 * </ul>
 * 磁盘写的是<b>入站处置后</b>的形态，与内存窗口一致：
 * 超大工具结果已落盘为 artifact、超大调用参数已卸载，账本因此只留引用。
 * 这与窗口同源（都是管线的输出），所以"账本里看到的"就是"模型当时看到的"。
 * <p>
 * 内容并未丢失：落盘的原文在 artifact 里，卸载的参数在被写入的工作区文件里——
 * 两条入站规则都以"已持久化"为前提才敢做替换。
 */
public class JsonlStore {
    private static final Logger log = LoggerFactory.getLogger(JsonlStore.class);

    private final Path jsonlFile;
    private final ObjectMapper mapper;
    private final ContextWindow window = new ContextWindow();
    private ContextPipeline pipeline;

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
     * 注入入站管线。装配期调用一次；未注入时消息原样进入窗口（无入站处置）。
     */
    public void setPipeline(ContextPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * 追加一条消息：经入站管线处置 → 进入内存窗口 → 写磁盘账本。
     * <p>
     * 顺序是先管线后账本，因为<b>账本记的是管线的输出</b>而非原始消息。
     * 若反过来写原文，单条记录会膨胀到十几万字符（大工具结果 / 大调用参数），
     * "一条消息一行"的约定就名存实亡了。
     *
     * @param turn               入站轮次
     * @param succeededToolCalls 本轮执行成功的工具调用 id（卸载的安全边界）
     */
    public synchronized void append(ChatMessage message, int turn, Set<String> succeededToolCalls) {
        if (pipeline != null) {
            List<ContextBlock> blocks = pipeline.ingest(message, turn, succeededToolCalls);
            window.addAll(blocks);
            List<ChatMessage> persisted = BlockProjector.assemble(blocks);
            appendToLedger(persisted.isEmpty() ? message : persisted.get(0));
        } else {
            window.addAll(BlockProjector.explode(message, "g" + window.size(), turn, null));
            appendToLedger(message);
        }
    }

    /**
     * 简化重载：无工具上下文时使用。
     */
    public synchronized void append(ChatMessage message, int turn) {
        append(message, turn, Collections.emptySet());
    }

    /**
     * 返回消息快照（由窗口的块组装而来，修改不影响内部视图）。
     */
    public synchronized List<ChatMessage> snapshot() {
        return window.toMessages();
    }

    /**
     * 内存窗口。压缩策略与度量直接作用于它。
     */
    public ContextWindow window() {
        return window;
    }

    /**
     * 用给定块列表整体替换内存窗口。
     * <p>
     * 只影响内存视图，不回写磁盘——磁盘账本保持完整历史，
     * 供摘要提示词中约定的检索回溯使用。
     */
    public synchronized void replaceAll(List<ChatMessage> compressed) {
        window.clear();
        for (int i = 0; i < compressed.size(); i++) {
            window.addAll(BlockProjector.explode(compressed.get(i), "r" + i, 0, null));
        }
        log.debug("上下文视图已更新: {} 个块", window.size());
    }

    private void appendToLedger(ChatMessage message) {
        String json = serialize(message);
        try {
            Files.writeString(jsonlFile, json + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("JSONL 追加失败: {}", e.getMessage(), e);
        }
    }

    private void loadAll() {
        try {
            List<String> lines = Files.readAllLines(jsonlFile);
            int turn = 0;
            for (String line : lines) {
                if (line.isBlank()) continue;
                ChatMessage msg = deserialize(line);
                if (msg != null) {
                    // 历史消息原样恢复：入站处置不回溯（否则会重复落盘 artifact）
                    window.addAll(BlockProjector.explode(msg, "h" + window.size(), turn, null));
                }
            }
            if (!lines.isEmpty()) {
                log.info("从 JSONL 加载 {} 条消息 → {} 个内容块", lines.size(), window.size());
            }
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
