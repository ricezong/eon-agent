package cn.kong.eon.tool;

import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.util.JsonMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;

import java.util.*;

/**
 * 工具描述符。包含名称、描述、权限、ToolSpecification 和执行器。
 * buildSpec 自动注入 reason 必填字段。
 */
public class ToolDescriptor {

    private final String name;
    private final String description;
    private final ToolPermission permission;
    private final ToolSpecification specification;
    private final ToolExecutor executor;

    public ToolDescriptor(String name, String description, ToolPermission permission,
                          ToolSpecification specification, ToolExecutor executor) {
        this.name = name;
        this.description = description;
        this.permission = permission;
        this.specification = specification;
        this.executor = executor;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public ToolPermission getPermission() { return permission; }
    public ToolSpecification getSpecification() { return specification; }
    public ToolExecutor getExecutor() { return executor; }

    /**
     * 提取工具调用的关键参数摘要，用于日志展示。
     * 默认实现：取前 80 字符的 JSON 摘要。可被子类覆盖。
     */
    public String summarizeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "(none)";
        String summary = switch (name) {
            case "web_search" -> {
                Object q = args.get("query");
                yield q != null ? "{query: \"" + truncate(String.valueOf(q), 60) + "\"}" : args.toString();
            }
            case "web_read", "download" -> {
                Object u = args.get("url");
                yield u != null ? "{url: \"" + truncate(String.valueOf(u), 60) + "\"}" : args.toString();
            }
            case "finish" -> {
                Object g = args.get("goal_achieved");
                yield "{goal_achieved: " + g + "}";
            }
            case "todo_write" -> {
                Object t = args.get("todos");
                int count = (t instanceof List<?> l) ? l.size() : 0;
                yield "{todos: " + count + " items}";
            }
            case "file_io" -> {
                Object op = args.get("operation");
                Object p = args.get("path");
                yield "{op: " + op + ", path: \"" + truncate(String.valueOf(p), 50) + "\"}";
            }
            default -> truncate(args.toString(), 80);
        };
        return truncate(summary, 80);
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * 构建工具 Schema，自动注入 reason 必填字段。
     * properties 中每个属性支持：type, description, required, items(array), properties(object)。
     */
    public static ToolSpecification buildSpec(String name, String description, Map<String, Map<String, Object>> properties) {
        JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();

        schemaBuilder.addStringProperty("reason", "为什么发起这次调用（必填，一句话说明动机）");

        if (properties != null) {
            for (Map.Entry<String, Map<String, Object>> entry : properties.entrySet()) {
                addProperty(schemaBuilder, entry.getKey(), entry.getValue());
            }
        }

        List<String> required = new ArrayList<>();
        required.add("reason");
        if (properties != null) {
            for (Map.Entry<String, Map<String, Object>> entry : properties.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue().get("required"))) {
                    required.add(entry.getKey());
                }
            }
        }
        schemaBuilder.required(required);

        return ToolSpecification.builder()
                .name(name)
                .description(description)
                .parameters(schemaBuilder.build())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static void addProperty(JsonObjectSchema.Builder schemaBuilder, String propName, Map<String, Object> propDef) {
        String type = (String) propDef.getOrDefault("type", "string");
        String desc = (String) propDef.getOrDefault("description", "");

        switch (type) {
            case "string" -> schemaBuilder.addStringProperty(propName, desc);
            case "integer" -> schemaBuilder.addIntegerProperty(propName, desc);
            case "number" -> schemaBuilder.addNumberProperty(propName, desc);
            case "boolean" -> schemaBuilder.addBooleanProperty(propName, desc);
            case "array" -> {
                JsonSchemaElement itemsSchema;
                Object itemsDef = propDef.get("items");
                if (itemsDef instanceof Map<?, ?> itemsMap) {
                    Map<String, Map<String, Object>> itemProps = (Map<String, Map<String, Object>>) itemsMap;
                    JsonObjectSchema.Builder itemBuilder = JsonObjectSchema.builder();
                    for (Map.Entry<String, Map<String, Object>> e : itemProps.entrySet()) {
                        addProperty(itemBuilder, e.getKey(), e.getValue());
                    }
                    List<String> itemRequired = new ArrayList<>();
                    for (Map.Entry<String, Map<String, Object>> e : itemProps.entrySet()) {
                        if (Boolean.TRUE.equals(e.getValue().get("required"))) {
                            itemRequired.add(e.getKey());
                        }
                    }
                    if (!itemRequired.isEmpty()) {
                        itemBuilder.required(itemRequired);
                    }
                    itemsSchema = itemBuilder.build();
                } else {
                    itemsSchema = JsonStringSchema.builder().build();
                }
                schemaBuilder.addProperty(propName,
                        JsonArraySchema.builder()
                                .description(desc)
                                .items(itemsSchema)
                                .build());
            }
            case "object" -> {
                Map<String, Map<String, Object>> objProps = (Map<String, Map<String, Object>>) propDef.get("properties");
                JsonObjectSchema.Builder objBuilder = JsonObjectSchema.builder();
                if (objProps != null) {
                    for (Map.Entry<String, Map<String, Object>> e : objProps.entrySet()) {
                        addProperty(objBuilder, e.getKey(), e.getValue());
                    }
                }
                schemaBuilder.addProperty(propName, objBuilder.build());
            }
            default -> schemaBuilder.addStringProperty(propName, desc);
        }
    }
}
