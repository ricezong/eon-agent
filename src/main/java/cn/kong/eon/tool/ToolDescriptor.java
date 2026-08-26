package cn.kong.eon.tool;

import cn.kong.eon.model.ToolPermission;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;

import java.util.List;

/**
 * 工具描述符。包含名称、描述、权限、ToolSpecification 和执行器。
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

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ToolPermission getPermission() {
        return permission;
    }

    public ToolSpecification getSpecification() {
        return specification;
    }

    public ToolExecutor getExecutor() {
        return executor;
    }

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
}


