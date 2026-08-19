package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;

import java.util.*;

/**
 * enable_tools 工具：模型通过结构化工具调用声明所需工具，引擎拦截后按需挂载对应 Schema。
 * 引擎在 executeTools 中拦截此工具的执行结果，从参数中提取工具名设置 pendingToolMounts。
 */
public class EnableToolsTool implements ToolExecutor {

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

        List<?> list;
        if (toolsRaw instanceof List<?> l) {
            list = l;
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
}
