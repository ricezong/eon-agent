package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.ArtifactSink;
import cn.kong.eon.agent.context.ToolSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 入站管线的执行上下文。规则通过它访问落盘存储、工具元数据与本轮的执行结果。
 */
public final class IngestContext {
    private static final Logger log = LoggerFactory.getLogger(IngestContext.class);

    private final ArtifactSink artifactSink;
    private final ToolSupport toolSupport;
    private final ObjectMapper objectMapper;
    private final int snipKeepChars;
    private final int offloadMinChars;
    private final Set<String> succeededToolCallIds;
    private final int turn;

    public IngestContext(ArtifactSink artifactSink,
                         ToolSupport toolSupport,
                         ObjectMapper objectMapper,
                         int snipKeepChars,
                         int offloadMinChars,
                         Set<String> succeededToolCallIds,
                         int turn) {
        this.artifactSink = artifactSink != null ? artifactSink : ArtifactSink.NONE;
        this.toolSupport = toolSupport != null ? toolSupport : ToolSupport.NONE;
        this.objectMapper = objectMapper;
        this.snipKeepChars = snipKeepChars;
        this.offloadMinChars = offloadMinChars;
        this.succeededToolCallIds = succeededToolCallIds != null
                ? succeededToolCallIds : Collections.emptySet();
        this.turn = turn;
    }

    public ArtifactSink artifactSink() {
        return artifactSink;
    }

    public ToolSupport toolSupport() {
        return toolSupport;
    }

    public int snipKeepChars() {
        return snipKeepChars;
    }

    public int offloadMinChars() {
        return offloadMinChars;
    }

    public int turn() {
        return turn;
    }

    /**
     * 参数 JSON 的序列化器。规则需要它来做参数卸载
     * （卸载必须保持严格合法 JSON，见 {@code ArgumentOffloader}）。
     */
    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    /**
     * 本次入站的工具调用 id 是否执行成功。
     * <p>
     * 只有成功的调用才能保证其参数已真正落盘，
     * 卸载失败的调用参数会永久丢失内容——所以必须校验。
     */
    public boolean succeeded(String toolCallId) {
        return toolCallId != null && succeededToolCallIds.contains(toolCallId);
    }

    /**
     * 解析参数 JSON。解析失败返回空 Map。
     */
    public Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank() || objectMapper == null) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.debug("[入站] 参数解析失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 序列化回 JSON。失败时返回 null，调用方据此回退到文本摘要。
     */
    public String toJson(Map<String, Object> args) {
        if (objectMapper == null) return null;
        try {
            return objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            log.debug("[入站] 参数序列化失败: {}", e.getMessage());
            return null;
        }
    }
}
