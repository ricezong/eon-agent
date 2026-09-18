package cn.kong.eon.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 工具服务。统一管理本地工具与远程工具（MCP）的元数据、Schema 与执行。
 */
public class ToolService {
    private static final Logger log = LoggerFactory.getLogger(ToolService.class);

    private final Map<String, ToolDescriptor> tools = new LinkedHashMap<>();
    private final Set<String> whitelist;
    private final ArgumentTypeCoercer coercer;
    private final ObjectMapper objectMapper;

    private final Map<String, RemoteToolInvoker> mcpToolSources = new HashMap<>();
    private final Map<String, ToolSpecification> mcpToolSpecs = new HashMap<>();

    public ToolService(Set<String> whitelist, ObjectMapper objectMapper) {
        this.whitelist = whitelist != null ? whitelist : new HashSet<>();
        this.objectMapper = objectMapper;
        this.coercer = new ArgumentTypeCoercer(objectMapper);
    }

    /** 注册本地工具（受白名单过滤）。 */
    public void register(ToolDescriptor descriptor) {
        if (!whitelist.isEmpty() && !whitelist.contains(descriptor.getName())) {
            log.warn("工具 {} 不在白名单中，跳过注册", descriptor.getName());
            return;
        }
        tools.put(descriptor.getName(), descriptor);
        log.info("本地工具已注册: {} [{}]", descriptor.getName(), descriptor.getPermission());
    }

    /**
     * 注册 MCP 工具。不受本地白名单限制。
     * @return 实际注册的工具数量
     */
    public int registerMcpTools(RemoteToolInvoker remoteTools, String permission) {
        ToolPermission perm = parsePermission(permission);
        List<ToolSpecification> toolSpecs = remoteTools.listTools();
        if (toolSpecs == null || toolSpecs.isEmpty()) {
            log.warn("远程工具服务无工具可注册: {}", remoteTools.serverKey());
            return 0;
        }
        int count = 0;
        for (ToolSpecification spec : toolSpecs) {
            String toolName = spec.name();
            mcpToolSources.put(toolName, remoteTools);
            mcpToolSpecs.put(toolName, spec);
            log.info("远程工具已注册: {} [{}] 来自服务 '{}'",
                    toolName, perm, remoteTools.serverKey());
            count++;
        }
        return count;
    }

    /** 解析权限字符串为枚举值。 */
    private ToolPermission parsePermission(String permission) {
        if (permission == null) return ToolPermission.READONLY;
        return switch (permission.toUpperCase()) {
            case "READONLY" -> ToolPermission.READONLY;
            case "RESTRICTED_WRITE", "RESTRICTEDWRITE" -> ToolPermission.RESTRICTED_WRITE;
            case "DESTRUCTIVE" -> ToolPermission.DESTRUCTIVE;
            default -> ToolPermission.READONLY;
        };
    }

    /** 获取本地工具描述符。 */
    public ToolDescriptor get(String name) {
        return tools.get(name);
    }

    /** 工具是否存在（本地或 MCP）。 */
    public boolean contains(String name) {
        return tools.containsKey(name) || mcpToolSpecs.containsKey(name);
    }

    /** 获取所有工具 Schema（本地 + MCP）。 */
    public List<ToolSpecification> getSpecifications() {
        List<ToolSpecification> all = new ArrayList<>();
        for (ToolDescriptor desc : tools.values()) {
            all.add(desc.getSpecification());
        }
        all.addAll(mcpToolSpecs.values());
        return all;
    }

    /** 执行工具（本地或 MCP），返回执行结果。 */
    public ToolResult execute(String name, Map<String, Object> arguments, ToolRuntime runtime) {
        ToolDescriptor descriptor = tools.get(name);
        if (descriptor != null) {
            try {
                // 根据工具 Schema 转换参数类型
                Map<String, Object> coerced = coercer.coerce(descriptor.getSpecification(), arguments);
                ToolResult result = descriptor.getExecutor().execute(coerced, runtime);
                log.debug("本地工具执行: {} -> 成功={} {} 字符", name, result.success(), result.content().length());
                return result;
            } catch (Exception e) {
                log.error("本地工具执行失败: {}", name, e);
                return ToolResult.failure("工具执行失败: " + e.getMessage());
            }
        }

        RemoteToolInvoker remoteTools = mcpToolSources.get(name);
        if (remoteTools != null) {
            try {
                String argsJson = convertArgsToJson(arguments);
                ToolResult result = remoteTools.invoke(name, argsJson);
                log.debug("远程工具执行: {} -> 成功={} {} 字符", name, result.success(), result.content().length());
                return result;
            } catch (Exception e) {
                log.error("远程工具执行失败: {}", name, e);
                return ToolResult.failure("远程工具执行失败: " + e.getMessage());
            }
        }

        return ToolResult.failure("工具不存在: " + name);
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

    /** 工具是否为破坏性权限。 */
    public boolean isDestructive(String name) {
        ToolPermission perm = getPermission(name);
        return perm == ToolPermission.DESTRUCTIVE;
    }

    /** 所有工具名称（本地 + MCP）。 */
    public Collection<String> getAllToolNames() {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(tools.keySet());
        names.addAll(mcpToolSpecs.keySet());
        return names;
    }

    /** 所有本地工具描述符。 */
    public Collection<ToolDescriptor> getAll() {
        return tools.values();
    }

    /** 释放所有本地工具持有的资源。 */
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

    /** 将参数 Map 转为 JSON 字符串（用于 MCP 工具调用）。 */
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
