package cn.kong.eon.store;

import cn.kong.eon.agent.context.CompressionState;
import cn.kong.eon.llm.TokenUsage;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 会话快照。同一会话内覆盖写，恢复时整体读回。
 * 只存恢复链路消费的三项：todo 列表、累计 token、压缩状态。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionSnapshot {
    private List<TodoItem> todoSnapshot;
    private TokenUsage usageAccum;
    private CompressionState compressionState;

    public SessionSnapshot() {
    }

    public List<TodoItem> getTodoSnapshot() {
        return todoSnapshot;
    }

    public void setTodoSnapshot(List<TodoItem> todoSnapshot) {
        this.todoSnapshot = todoSnapshot;
    }

    public TokenUsage getUsageAccum() {
        return usageAccum;
    }

    public void setUsageAccum(TokenUsage usageAccum) {
        this.usageAccum = usageAccum;
    }

    public CompressionState getCompressionState() {
        return compressionState;
    }

    public void setCompressionState(CompressionState compressionState) {
        this.compressionState = compressionState;
    }
}
