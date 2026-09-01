package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.ArtifactSink;

import java.util.Collections;
import java.util.Set;

/**
 * 入站管线的执行上下文。规则通过它访问落盘存储与本轮的执行结果。
 */
public final class IngestContext {

    private final ArtifactSink artifactSink;
    private final int snipKeepChars;
    private final Set<String> succeededToolCallIds;
    private final int turn;

    public IngestContext(ArtifactSink artifactSink,
                         int snipKeepChars,
                         Set<String> succeededToolCallIds,
                         int turn) {
        this.artifactSink = artifactSink != null ? artifactSink : ArtifactSink.NONE;
        this.snipKeepChars = snipKeepChars;
        this.succeededToolCallIds = succeededToolCallIds != null
                ? succeededToolCallIds : Collections.emptySet();
        this.turn = turn;
    }

    public ArtifactSink artifactSink() {
        return artifactSink;
    }

    public int snipKeepChars() {
        return snipKeepChars;
    }

    public int turn() {
        return turn;
    }

    /** 本次入站的工具调用 id 是否执行成功。卸载失败的调用参数会永久丢失内容。 */
    public boolean succeeded(String toolCallId) {
        return toolCallId != null && succeededToolCallIds.contains(toolCallId);
    }
}
