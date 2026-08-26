package cn.kong.eon.tool;

import cn.kong.eon.tool.mcp.McpClientManager;
import cn.kong.eon.model.ToolPermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 工具注册表。统一管理本地工具和 MCP 工具的元数据与执行。
 */
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolDescriptor> tools = new LinkedHashMap<>();
    private final Set<String> whitelist;
    private final ObjectMapper objectMapper;
    private final ArgumentSanitizer sanitizer;

    private final Map<String, McpClientManager> mcpToolSources = new HashMap<>();
    private final Map<String, ToolSpecification> mcpToolSpecs = new HashMap<>();

    public ToolRegistry(Set<String> whitelist, ObjectMapper objectMapper) {
        this.whitelist = whitelist != null ? whitelist : new HashSet<>();
        this.objectMapper = objectMapper;
        this.sanitizer = new ArgumentSanitizer(objectMapper);
    }

    /**
     * 注册本地工具（受白名单过滤）。
     */
    public void register(ToolDescriptor descriptor) {
        if (!whitelist.isEmpty() && !whitelist.contains(descriptor.getName())) {
            log.warn("工具 {} 不在白名单中，跳过注册", descriptor.getName());
            return;
        }
        tools.put(descriptor.getName(), descriptor);
        log.info("本地工具已注册: {} [{}]", descriptor.getName(), descriptor.getPermission());
    }

    /**
     * 注册 MCP 工具。MCP 工具不受本地白名单限制。
     *
     * @return 实际注册的工具数量
     */
    public int registerMcpTools(McpClientManager mcpManager, String permission) {
        ToolPermission perm = parsePermission(permission);
        List<ToolSpecification> toolSpecs = mcpManager.listTools();
        if (toolSpecs == null || toolSpecs.isEmpty()) {
            log.warn("MCP 服务无工具可注册: {}", mcpManager.getServerKey());
            return 0;
        }
        int count = 0;
        for (ToolSpecification spec : toolSpecs) {
            String toolName = spec.name();
            mcpToolSources.put(toolName, mcpManager);
            mcpToolSpecs.put(toolName, spec);
            log.info("MCP 工具已注册: {} [{}] 来自服务 '{}'",
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

    public ToolDescriptor get(String name) {
        return tools.get(name);
    }

    /**
     * 判断工具是否存在（本地或 MCP）。
     */
    public boolean contains(String name) {
        return tools.containsKey(name) || mcpToolSpecs.containsKey(name);
    }

    public boolean isMcpTool(String name) {
        return mcpToolSpecs.containsKey(name);
    }

    /**
     * 获取所有工具 Schema（本地 + MCP）。
     */
    public List<ToolSpecification> getSpecifications() {
        List<ToolSpecification> all = new ArrayList<>();
        for (ToolDescriptor desc : tools.values()) {
            all.add(desc.getSpecification());
        }
        all.addAll(mcpToolSpecs.values());
        return all;
    }

    /**
     * 执行工具（本地或 MCP）。
     */
    public ToolOutcome execute(String name, Map<String, Object> arguments,
                               cn.kong.eon.model.SessionState state, ToolContext context) {
        ToolDescriptor descriptor = tools.get(name);
        if (descriptor != null) {
            try {
                // 根据工具 Schema 清洗参数类型（处理 LLM 输出类型不规范的问题）
                Map<String, Object> sanitized = sanitizer.sanitize(descriptor.getSpecification(), arguments);
                ToolOutcome result = descriptor.getExecutor().execute(sanitized, state, context);
                log.debug("本地工具执行: {} -> 成功={} {} 字符", name, result.success(), result.content().length());
                return result;
            } catch (Exception e) {
                log.error("本地工具执行失败: {}", name, e);
                return ToolOutcome.failure("工具执行失败: " + e.getMessage());
            }
        }

        McpClientManager mcpManager = mcpToolSources.get(name);
        if (mcpManager != null) {
            try {
                String argsJson = convertArgsToJson(arguments);
                ToolOutcome result = mcpManager.executeTool(name, argsJson);
                log.debug("MCP 工具执行: {} -> 成功={} {} 字符", name, result.success(), result.content().length());
                return result;
            } catch (Exception e) {
                log.error("MCP 工具执行失败: {}", name, e);
                return ToolOutcome.failure("MCP 工具执行失败: " + e.getMessage());
            }
        }

        return ToolOutcome.failure("工具不存在: " + name);
    }

    /**
     * 获取工具权限（MCP 工具默认 READONLY）。
     */
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


    public Set<String> getWhitelist() {
        return Collections.unmodifiableSet(whitelist);
    }

    public Collection<String> getAllToolNames() {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(tools.keySet());
        names.addAll(mcpToolSpecs.keySet());
        return names;
    }

    public Collection<ToolDescriptor> getAll() {
        return tools.values();
    }

    public int getMcpToolCount() {
        return mcpToolSpecs.size();
    }

    /**
     * 释放所有本地工具持有的资源（如 Scanner、文件句柄等）。
     */
    public void closeAll() {
        for (ToolDescriptor desc : tools.values()) {
            try {
                desc.getExecutor().close();
            } catch (Exception e) {
                log.warn("关闭工具 {} 失败: {}", desc.getName(), e.getMessage());
            }
        }
        log.info("所有本地工具已关闭（{}）", tools.size());
    }

    private String convertArgsToJson(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (Exception e) {
            log.warn("参数转 JSON 失败: {}", arguments, e);
            return "{}";
        }
    }
}
