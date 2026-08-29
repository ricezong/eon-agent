package cn.kong.eon.agent.context;

import cn.kong.eon.agent.context.block.ToolMetaLookup;

/**
 * 上下文层对工具层的依赖倒置接口。
 * <p>
 * context 包需要知道两件关于工具的事，但不应该因此依赖 tool 包的任何具体类；
 * 由 {@code ToolRegistry} 实现本接口并在装配期注入。
 * <p>
 * 继承 {@link ToolMetaLookup}：投射层只需要"参数是否落盘"这一条判据，
 * 入站管线需要额外的摘要能力，两者是同一个概念的粗细两级，而不是两个接口。
 */
public interface ToolSupport extends ToolMetaLookup {

    /** 无工具可用时的实现：所有查询返回"未持久化"。 */
    ToolSupport NONE = new ToolSupport() {
        @Override
        public boolean persistsArguments(String toolName) {
            return false;
        }

        @Override
        public String summarizeArgs(String toolName, String argumentsJson) {
            return null;
        }
    };

    /**
     * 工具参数的短摘要（如 {@code {path: "report.html"}}）。
     *
     * @return 摘要文本；无法解析或工具不存在时返回 null
     */
    String summarizeArgs(String toolName, String argumentsJson);
}
