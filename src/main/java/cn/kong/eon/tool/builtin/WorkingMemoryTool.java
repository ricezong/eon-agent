package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolOutcome;

import java.util.LinkedHashMap;
import java.util.Map;

/** working_memory 工具：写入关键发现到 Insights，防止上下文压缩后丢失。 */
public class WorkingMemoryTool implements ToolExecutor {

    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("insight", Map.of(
                "type", "string",
                "description", "关键发现（一句话）。如：找到下载链接 http://...；web_search 持续超时；用户偏好简洁回复",
                "required", true
        ));
        String desc = "记录关键发现到 Insights 滚动区（上限 40 条，旧自动淘汰）。"
                + "用于跨轮次保留重要信息，防止上下文压缩后丢失。";
        return new ToolDescriptor(
                "working_memory",
                desc,
                ToolPermission.RESTRICTED_WRITE,
                ToolDescriptor.buildSpec("working_memory", desc, props),
                new WorkingMemoryTool()
        );
    }

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        String insight = (String) arguments.get("insight");
        if (insight == null || insight.isBlank()) {
            return ToolOutcome.failure("Missing 'insight' parameter");
        }

        context.insightsStore().add(insight);

        return ToolOutcome.success("已记录关键发现：" + insight + "\n当前 Insights 共 " + context.insightsStore().size() + " 条");
    }
}
