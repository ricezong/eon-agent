package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.StoreSupport;
import cn.kong.eon.agent.context.ToolSupport;

import java.util.Collections;
import java.util.Set;

/**
 * 入站管线的执行上下文。规则通过它访问落盘存储、工具属性与本轮的执行结果。
 * <p>
 * 上下文里没有轮次——块不记录自己属于哪一轮，保护区的分界由窗口按位置下标计算。
 */
public final class IngestContext {

    private final StoreSupport storeSupport;
    private final ToolSupport toolSupport;
    private final int snipKeepChars;
    private final Set<String> succeededToolCallIds;

    public IngestContext(StoreSupport storeSupport,
                         ToolSupport toolSupport,
                         int snipKeepChars,
                         Set<String> succeededToolCallIds) {
        this.storeSupport = storeSupport;
        this.toolSupport = toolSupport;
        this.snipKeepChars = snipKeepChars;
        this.succeededToolCallIds = succeededToolCallIds != null ? succeededToolCallIds : Collections.emptySet();
    }

    public StoreSupport storeSupport() {
        return storeSupport;
    }

    /** 工具属性查询，用于判断工具是否会持久化其调用参数。 */
    public ToolSupport toolSupport() {
        return toolSupport;
    }

    public int snipKeepChars() {
        return snipKeepChars;
    }

    /** 本次入站的工具调用 id 是否执行成功。 */
    public boolean succeeded(String toolCallId) {
        return toolCallId != null && succeededToolCallIds.contains(toolCallId);
    }
}
