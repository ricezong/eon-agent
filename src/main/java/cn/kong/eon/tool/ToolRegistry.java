package cn.kong.eon.tool;

import cn.kong.eon.mcp.McpClientManager;
import cn.kong.eon.model.ToolPermission;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 工具注册表。统一管理本地工具和 MCP 工具的元数据与执行。
 * 本地工具通过 ToolDescriptor 注册；MCP 工具通过 registerMcpTools() 注册，委托 McpClientManager 执行。
 */
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolDescriptor> tools = new LinkedHashMap<>();
    private final Set<String> whitelist;
    private final ArgumentSanitizer sanitizer = new ArgumentSanitizer();

    // MCP 工具
    private final Map<String, McpClientManager> mcpToolSources = new HashMap<>();
    private final Map<String, ToolSpecification> mcpToolSpecs = new HashMap<>();

    public ToolRegistry(Set<String> whitelist) {
        this.whitelist = whitelist != null ? whitelist : new HashSet<>();
    }

    /** 注册本地工具（受白名单过滤）。 */
    public void register(ToolDescriptor descriptor) {
        if (!whitelist.isEmpty() && !whitelist.contains(descriptor.getName())) {
            log.warn("Tool {} not in whitelist, skip registration", descriptor.getName());
            return;
        }
        tools.put(descriptor.getName(), descriptor);
        log.info("Local tool registered: {} [{}]", descriptor.getName(), descriptor.getPermission());
    }

    /**
     * 注册 MCP 工具。MCP 工具不受本地白名单限制。
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

    public ToolDescriptor get(String name) { return tools.get(name); }

    /** 判断工具是否存在（本地或 MCP）。 */
    public boolean contains(String name) {
        return tools.containsKey(name) || mcpToolSpecs.containsKey(name);
    }

    public boolean isMcpTool(String name) { return mcpToolSpecs.containsKey(name); }

    /** 获取所有工具 Schema（本地 + MCP）。 */
    public List<ToolSpecification> getSpecifications() {
        List<ToolSpecification> all = new ArrayList<>();
        for (ToolDescriptor desc : tools.values()) {
            all.add(desc.getSpecification());
        }
        all.addAll(mcpToolSpecs.values());
        return all;
    }

    /** 按名称过滤获取 Schema */
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

    /** 执行工具（本地或 MCP）。 */
    public ToolOutcome execute(String name, Map<String, Object> arguments,
                          cn.kong.eon.model.SessionState state, ToolContext context) {
        // 本地工具
        ToolDescriptor descriptor = tools.get(name);
        if (descriptor != null) {
            try {
                // 根据工具 Schema 清洗参数类型（处理 LLM 输出类型不规范的问题）
                Map<String, Object> sanitized = sanitizer.sanitize(descriptor.getSpecification(), arguments);
                ToolOutcome result = descriptor.getExecutor().execute(sanitized, state, context);
                log.debug("Local tool executed: {} -> success={} {} chars", name, result.success(), result.content().length());
                return result;
            } catch (Exception e) {
                log.error("Local tool execution failed: {}", name, e);
                return ToolOutcome.failure("Tool execution failed: " + e.getMessage());
            }
        }

        // MCP 工具
        McpClientManager mcpManager = mcpToolSources.get(name);
        if (mcpManager != null) {
            try {
                String argsJson = convertArgsToJson(arguments);
                ToolOutcome result = mcpManager.executeTool(name, argsJson);
                log.debug("MCP tool executed: {} -> success={} {} chars", name, result.success(), result.content().length());
                return result;
            } catch (Exception e) {
                log.error("MCP tool execution failed: {}", name, e);
                return ToolOutcome.failure("MCP tool execution failed: " + e.getMessage());
            }
        }

        return ToolOutcome.failure("Tool not found: " + name);
    }

    /** 获取工具权限（MCP 工具默认 READONLY）。 */
    public ToolPermission getPermission(String name) {
        ToolDescriptor descriptor = tools.get(name);
        if (descriptor != null) {
            return descriptor.getPermission();
        }
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

    public Set<String> getWhitelist() { return Collections.unmodifiableSet(whitelist); }

    public Collection<String> getAllToolNames() {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(tools.keySet());
        names.addAll(mcpToolSpecs.keySet());
        return names;
    }

    public Collection<ToolDescriptor> getAll() { return tools.values(); }
    public int getMcpToolCount() { return mcpToolSpecs.size(); }

    /** 获取工具目录摘要（名称 + 描述），用于 SIMPLE 模式懒加载。 */
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

    private String convertArgsToJson(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return cn.kong.eon.util.JsonMapper.get().writeValueAsString(arguments);
        } catch (Exception e) {
            log.warn("Failed to convert args to JSON: {}", arguments, e);
            return "{}";
        }
    }
}
