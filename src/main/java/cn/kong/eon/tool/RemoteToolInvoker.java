package cn.kong.eon.tool;

import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;

/**
 * 远程工具调用端口。定义在 tool 包内，由 tool.mcp 侧的适配器实现，
 * 使 tool 不再反向依赖 tool.mcp——MCP 只是一种可插拔的远程工具来源。
 */
public interface RemoteToolInvoker {

    /** 远程工具服务标识（用于日志与错误信息）。 */
    String serverKey();

    /** 列出远程服务提供的工具 Schema。 */
    List<ToolSpecification> listTools();

    /** 执行远程工具调用，argumentsJson 为 JSON 字符串。 */
    ToolOutcome invoke(String toolName, String argumentsJson);
}
