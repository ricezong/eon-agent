package cn.kong.eon.tool;

import cn.kong.eon.model.ToolPermission;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.request.json.*;

import java.util.*;

/** 工具描述符。包含名称、描述、权限、ToolSpecification 和执行器。 */
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
     * 从 @Tool 注解方法自动构建 ToolDescriptor。
     * 使用 LangChain4j 的 ToolSpecifications 扫描注解方法生成 ToolSpecification。
     */
    public static ToolDescriptor fromAnnotated(ToolExecutor executor, ToolPermission permission) {
        List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(executor);
        if (specs.isEmpty()) {
            throw new IllegalArgumentException(
                    "No @Tool-annotated methods found on " + executor.getClass().getName());
        }
        ToolSpecification spec = specs.get(0);
        return new ToolDescriptor(spec.name(), spec.description(), permission, spec, executor);
    }

    /** 提取工具调用的关键参数摘要，用于日志展示。 */
    public String summarizeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "(none)";
        String summary = switch (name) {
            case "web_search" -> {
                Object q = args.get("query");
                yield q != null ? "{query: \"" + truncate(String.valueOf(q), 60) + "\"}" : args.toString();
            }
            case "read_file", "write", "delete_file", "list_dir" -> {
                Object p = args.get("target_file");
                if (p == null) p = args.get("file_path");
                if (p == null) p = args.get("target_directory");
                yield p != null ? "{path: \"" + truncate(String.valueOf(p), 50) + "\"}" : args.toString();
            }
            case "grep" -> {
                Object pat = args.get("pattern");
                yield pat != null ? "{pattern: \"" + truncate(String.valueOf(pat), 50) + "\"}" : args.toString();
            }
            case "todo_write" -> {
                Object t = args.get("todos");
                int count = (t instanceof List<?> l) ? l.size() : 0;
                yield "{todos: " + count + " items}";
            }
            case "update_memory" -> {
                Object a = args.get("action");
                Object t = args.get("title");
                yield "{action: " + a + ", title: \"" + truncate(String.valueOf(t), 40) + "\"}";
            }
            case "web_fetch" -> {
                Object u = args.get("urls");
                int count = (u instanceof List<?> l) ? l.size() : 0;
                yield "{urls: " + count + "}";
            }
            case "AskQuestion" -> {
                Object q = args.get("questions");
                int count = (q instanceof List<?> l) ? l.size() : 0;
                yield "{questions: " + count + "}";
            }
            default -> truncate(args.toString(), 80);
        };
        return truncate(summary, 80);
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * 构建工具 Schema。
     * properties 中每个属性支持：type, description, required, items(array), properties(object)。
     */
    public static ToolSpecification buildSpec(String name, String description, Map<String, Map<String, Object>> properties) {
        JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();

        if (properties != null) {
            for (Map.Entry<String, Map<String, Object>> entry : properties.entrySet()) {
                addProperty(schemaBuilder, entry.getKey(), entry.getValue());
            }
        }

        List<String> required = new ArrayList<>();
        if (properties != null) {
            for (Map.Entry<String, Map<String, Object>> entry : properties.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue().get("required"))) {
                    required.add(entry.getKey());
                }
            }
        }
        if (!required.isEmpty()) {
            schemaBuilder.required(required);
        }

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
