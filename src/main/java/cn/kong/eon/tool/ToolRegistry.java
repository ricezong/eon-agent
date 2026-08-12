package cn.kong.eon.tool;

import cn.kong.eon.mcp.McpClientManager;
import cn.kong.eon.model.ToolPermission;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 工具注册表。
 * 对应技术方案第 5.4 节。
 * 统一管理所有工具的元数据与执行管线。
 * 对 Agent Loop 提供一致的调用接口。
 *
 * 支持两类工具：
 * 1. 本地工具：通过 ToolDescriptor 注册，由 ToolExecutor 执行
 * 2. MCP 工具：通过 registerMcpTools() 注册，由 McpClientManager 委托执行
 */
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolDescriptor> tools = new LinkedHashMap<>();
    private final Set<String> whitelist;

    // MCP 工具：toolName -> McpClientManager
    private final Map<String, McpClientManager> mcpToolSources = new HashMap<>();
    // MCP 工具的 ToolSpecification（直接复用 MCP 服务返回的 schema）
    private final Map<String, ToolSpecification> mcpToolSpecs = new HashMap<>();

    public ToolRegistry(Set<String> whitelist) {
        this.whitelist = whitelist != null ? whitelist : new HashSet<>();
    }

    /**
     * 注册本地工具。
     */
    public void register(ToolDescriptor descriptor) {
        if (!whitelist.isEmpty() && !whitelist.contains(descriptor.getName())) {
            log.warn("Tool {} not in whitelist, skip registration", descriptor.getName());
            return;
        }
        tools.put(descriptor.getName(), descriptor);
        log.info("Local tool registered: {} [{}]", descriptor.getName(), descriptor.getPermission());
    }

    /**
     * 注册 MCP 工具。
     * 从 McpClientManager 获取工具列表，全部注册。
     *
     * @param mcpManager MCP 客户端管理器
     * @param permission MCP 工具的默认权限（READONLY / RESTRICTED_WRITE / DESTRUCTIVE）
     * @return 实际注册的工具数量
     */
    public int registerMcpTools(McpClientManager mcpManager, String permission) {
        ToolPermission perm = parsePermission(permission);
        List<ToolSpecification> toolSpecs = mcpManager.listTools();
        if (toolSpecs == null || toolSpecs.isEmpty()) {
            log.warn("No MCP tools to register from server: {}", mcpManager.getServerKey());
            return 0;
        }
        int count = 0;
        for (ToolSpecification spec : toolSpecs) {
            String toolName = spec.name();
            // MCP 工具默认全部放行，不受本地工具白名单限制
            // （白名单只用于过滤本地工具；MCP 工具由 MCP 服务的权限控制）
            mcpToolSources.put(toolName, mcpManager);
            mcpToolSpecs.put(toolName, spec);
            log.info("MCP tool registered: {} [{}] from server '{}'",
                    toolName, perm, mcpManager.getServerKey());
            count++;
        }
        return count;
    }

    private ToolPermission parsePermission(String permission) {
        if (permission == null) return ToolPermission.READONLY;
        return switch (permission.toUpperCase()) {
            case "READONLY" -> ToolPermission.READONLY;
            case "RESTRICTED_WRITE", "RESTRICTEDWRITE" -> ToolPermission.RESTRICTED_WRITE;
            case "DESTRUCTIVE" -> ToolPermission.DESTRUCTIVE;
            default -> ToolPermission.READONLY;
        };
    }

    /**
     * 获取工具描述符（仅本地工具）。
     */
    public ToolDescriptor get(String name) {
        return tools.get(name);
    }

    /**
     * 判断工具是否存在（本地或 MCP）。
     */
    public boolean contains(String name) {
        return tools.containsKey(name) || mcpToolSpecs.containsKey(name);
    }

    /**
     * 判断是否为 MCP 工具。
     */
    public boolean isMcpTool(String name) {
        return mcpToolSpecs.containsKey(name);
    }

    /**
     * 获取所有工具的 ToolSpecification（本地 + MCP，用于传给 LLM）。
     */
    public List<ToolSpecification> getSpecifications() {
        List<ToolSpecification> all = new ArrayList<>();
        // 本地工具
        for (ToolDescriptor desc : tools.values()) {
            all.add(desc.getSpecification());
        }
        // MCP 工具
        all.addAll(mcpToolSpecs.values());
        return all;
    }

    /**
     * 获取指定工具名的 Schema 列表（按名称过滤）。
     * 用于 ASSISTED Profile：只注入网络搜索相关工具。
     */
    public List<ToolSpecification> getSpecificationsByName(Set<String> toolNames) {
        List<ToolSpecification> result = new ArrayList<>();
        for (String name : toolNames) {
            ToolDescriptor desc = tools.get(name);
            if (desc != null) {
                result.add(desc.getSpecification());
            }
            ToolSpecification mcpSpec = mcpToolSpecs.get(name);
            if (mcpSpec != null) {
                result.add(mcpSpec);
            }
        }
        return result;
    }

    /**
     * 执行工具（本地或 MCP）。
     */
    public String execute(String name, Map<String, Object> arguments,
                          cn.kong.eon.model.SessionState state, ToolContext context) {
        // 本地工具
        ToolDescriptor descriptor = tools.get(name);
        if (descriptor != null) {
            try {
                String result = descriptor.getExecutor().execute(arguments, state, context);
                log.debug("Local tool executed: {} -> {} chars", name, result != null ? result.length() : 0);
                return result;
            } catch (Exception e) {
                log.error("Local tool execution failed: {}", name, e);
                return "[ERROR] Tool execution failed: " + e.getMessage();
            }
        }

        // MCP 工具
        McpClientManager mcpManager = mcpToolSources.get(name);
        if (mcpManager != null) {
            try {
                String argsJson = convertArgsToJson(arguments);
                String result = mcpManager.executeTool(name, argsJson);
                log.debug("MCP tool executed: {} -> {} chars", name, result != null ? result.length() : 0);
                return result;
            } catch (Exception e) {
                log.error("MCP tool execution failed: {}", name, e);
                return "[ERROR] MCP tool execution failed: " + e.getMessage();
            }
        }

        return "[ERROR] Tool not found: " + name;
    }

    /**
     * 获取工具权限。
     * 本地工具从 ToolDescriptor 获取；MCP 工具默认 READONLY。
     */
    public ToolPermission getPermission(String name) {
        ToolDescriptor descriptor = tools.get(name);
        if (descriptor != null) {
            return descriptor.getPermission();
        }
        // MCP 工具默认只读
        if (mcpToolSpecs.containsKey(name)) {
            return ToolPermission.READONLY;
        }
        return null;
    }

    public boolean isDestructive(String name) {
        ToolPermission perm = getPermission(name);
        return perm == ToolPermission.DESTRUCTIVE;
    }

    public boolean isReadonly(String name) {
        ToolPermission perm = getPermission(name);
        return perm == ToolPermission.READONLY;
    }

    public Set<String> getWhitelist() {
        return Collections.unmodifiableSet(whitelist);
    }

    /**
     * 获取所有已注册工具名（本地 + MCP）。
     */
    public Collection<String> getAllToolNames() {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(tools.keySet());
        names.addAll(mcpToolSpecs.keySet());
        return names;
    }

    /**
     * 获取本地工具描述符集合（不含 MCP 工具）。
     */
    public Collection<ToolDescriptor> getAll() {
        return tools.values();
    }

    /**
     * 获取 MCP 工具数量。
     */
    public int getMcpToolCount() {
        return mcpToolSpecs.size();
    }

    /**
     * 获取工具目录摘要（名称 + 一句话描述）。
     * 用于 ASSISTED Profile 的懒加载：先注入摘要，LLM 需要时再加载完整 Schema。
     */
    public String getCatalogSummary() {
        StringBuilder sb = new StringBuilder("可用工具目录：\n");
        for (ToolDescriptor desc : tools.values()) {
            sb.append("- ").append(desc.getName())
              .append(": ").append(desc.getDescription()).append("\n");
        }
        for (Map.Entry<String, ToolSpecification> entry : mcpToolSpecs.entrySet()) {
            String name = entry.getKey();
            String desc = entry.getValue().description();
            sb.append("- ").append(name)
              .append(": ").append(desc != null ? desc : "").append("\n");
        }
        return sb.toString();
    }

    /**
     * 将参数 Map 转为 JSON 字符串（用于 MCP 调用）。
     */
    private String convertArgsToJson(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(arguments);
        } catch (Exception e) {
            log.warn("Failed to convert args to JSON: {}", arguments, e);
            return "{}";
        }
    }
}
