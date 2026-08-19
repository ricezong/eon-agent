package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * enable_tools 工具：模型通过结构化工具调用声明所需工具，引擎拦截后按需挂载对应 Schema。
 * 引擎在 executeTools 中拦截此工具的执行结果，从参数中提取工具名设置 pendingToolMounts。
 */
public class EnableToolsTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(EnableToolsTool.class);

    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("tools", Map.of(
                "type", "array",
                "description", "你需要使用的工具名称列表，名称必须与 tool_catalog 中完全一致",
                "required", true,
                "items", "string"
        ));

        String desc = "声明你需要的工具。调用后下一轮将获得这些工具的完整调用参数。";
        return new ToolDescriptor(
                "enable_tools",
                desc,
                ToolPermission.READONLY,
                ToolDescriptor.buildSpec("enable_tools", desc, props),
                new EnableToolsTool()
        );
    }

    @Override
    public String execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        Object toolsRaw = arguments.get("tools");
        if (toolsRaw == null) {
            return "[ERROR] 缺少 'tools' 参数。请传入工具名称数组，例如：[\"web_search\", \"web_read\"]";
        }
        // 容错：LLM 有时把数组序列化成字符串
        List<?> list;
        if (toolsRaw instanceof List<?> l) {
            list = l;
        } else if (toolsRaw instanceof String s) {
            list = parseJsonArray(s);
            if (list == null) {
                return "[ERROR] 'tools' 必须是数组。当前是 String 且无法解析为 JSON 数组。"
                        + "请传入真正的数组，例如：[\"web_search\", \"web_read\"]";
            }
        } else {
            return "[ERROR] 'tools' 必须是数组。当前类型: " + toolsRaw.getClass().getSimpleName();
        }


        if (list.isEmpty()) {
            return "[ERROR] 'tools' 不能为空";
        }

        Set<String> tools = new LinkedHashSet<>();
        for (Object item : list) {
            tools.add(String.valueOf(item).trim());
        }

        return "已声明工具: " + String.join(", ", tools) + "。下一轮将获得完整调用参数。";
    }

    /** 容错解析：支持数组字符串和单对象字符串。 */
    private List<?> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return null;
        String trimmed = json.trim();
        try {
            ObjectMapper mapper = new ObjectMapper();
            Object parsed = mapper.readValue(trimmed, new TypeReference<Object>() {});
            if (parsed instanceof List<?> l) {
                return l;
            }
            if (parsed instanceof Map<?, ?> m) {
                return List.of(m);
            }
            return null;
        } catch (Exception e) {
            log.debug("tools: 字符串解析为 JSON 数组失败: {}", e.getMessage());
            return null;
        }
    }
}
