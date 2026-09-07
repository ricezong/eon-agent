package cn.kong.eon.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 会话快照。同一会话内覆盖写（session.json），恢复时整体读回。
 * <p>
 * 只存恢复链路真正消费的三项：todo 列表、累计 token、压缩状态。
 * 会话 id 已由所在目录名表达、写入时刻已由文件 mtime 表达，不再重复落盘。
 * 压缩状态内的摘要与回放水位线必须成对一致，恢复流程据此定位账本回放起点。
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
