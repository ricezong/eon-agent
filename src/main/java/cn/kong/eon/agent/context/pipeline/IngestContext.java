package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.StoreSupport;

import java.util.Collections;
import java.util.Set;

/**
 * 入站管线的执行上下文。规则通过它访问落盘存储与本轮的执行结果。
 * <p>
 * 上下文里没有轮次——块不记录自己属于哪一轮，保护区的分界由窗口按位置下标计算。
 */
public final class IngestContext {

    private final StoreSupport storeSupport;
    private final int spillThresholdChars;
    private final int spillKeepChars;
    private final Set<String> succeededToolCallIds;

    public IngestContext(StoreSupport storeSupport,
                         int spillThresholdChars,
                         int spillKeepChars,
                         Set<String> succeededToolCallIds) {
        this.storeSupport = storeSupport;
        this.spillThresholdChars = spillThresholdChars;
        this.spillKeepChars = spillKeepChars;
        this.succeededToolCallIds = succeededToolCallIds != null ? succeededToolCallIds : Collections.emptySet();
    }

    public StoreSupport storeSupport() {
        return storeSupport;
    }

    /** 工具结果超过此长度才落盘。 */
    public int spillThresholdChars() {
        return spillThresholdChars;
    }

    /** 落盘后块里保留的头尾摘要长度。 */
    public int spillKeepChars() {
        return spillKeepChars;
    }

    /** 本次入站的工具调用 id 是否执行成功。 */
    public boolean succeeded(String toolCallId) {
        return toolCallId != null && succeededToolCallIds.contains(toolCallId);
    }
}
