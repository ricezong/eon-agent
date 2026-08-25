package cn.kong.eon.agent.hook.pretool;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 门禁校验（PreTool, order=20）。
 * 检查破坏性工具的必要参数是否提供，校验失败请求优雅停止。
 * 每个破坏性工具有自己的必填参数，不再统一检查 url。
 */
public class GateHook implements Hook.PreToolHook {
    private static final Logger log = LoggerFactory.getLogger(GateHook.class);

    private final int stopGraceSteps;

    /** 破坏性工具 → 必填参数名映射。 */
    private static final Map<String, String> DESTRUCTIVE_REQUIRED_PARAMS = Map.of(
            "delete_file", "target_file"
    );

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public GateHook(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this(toolRegistry, objectMapper, 2);
    }

    public GateHook(ToolRegistry toolRegistry, ObjectMapper objectMapper, int stopGraceSteps) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.stopGraceSteps = stopGraceSteps;
    }

    @Override public String name() { return "Gate"; }
    @Override public boolean isActive(SessionState state) { return true; }
    @Override public int order() { return 20; }

    @Override
    public HookResult beforeToolExecution(SessionState state, List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) return HookResult.ok();

        for (ToolExecutionRequest req : requests) {
            if (!toolRegistry.isDestructive(req.name())) continue;

            log.warn("[PreTool] Gate: destructive '{}' approved | args: {} | turn: {}",
                    req.name(), req.arguments(), state.getTurnCount());

            String requiredParam = DESTRUCTIVE_REQUIRED_PARAMS.get(req.name());
            if (requiredParam == null) continue;  // 无映射的破坏性工具跳过参数校验

            String value = extractParam(req.arguments(), requiredParam);
            if (value == null || value.isBlank()) {
                log.warn("[PreTool] Gate: REJECTED '{}' missing required param '{}' → STOP",
                        req.name(), requiredParam);
                StopReason reason = new StopReason(
                        StopCategory.GATE_REJECTED,
                        "破坏性工具 " + req.name() + " 缺少必要参数 " + requiredParam,
                        stopGraceSteps);
                return HookResult.stop(reason);
            }
        }
        return HookResult.ok();
    }

    /** 从 JSON 参数中提取指定字段值。 */
    private String extractParam(String argumentsJson, String fieldName) {
        if (argumentsJson == null || argumentsJson.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(argumentsJson);
            if (node.has(fieldName)) return node.get(fieldName).asText();
            return null;
        } catch (Exception e) {
            log.warn("[PreTool] Gate: failed to parse arguments: {}", argumentsJson);
            return null;
        }
    }
}
