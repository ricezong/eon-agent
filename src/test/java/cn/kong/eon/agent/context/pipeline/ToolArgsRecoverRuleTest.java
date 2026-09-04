package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.StoreSupport;
import cn.kong.eon.agent.context.ToolSupport;
import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.model.ArtifactRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具参数可恢复性入站规则的特征测试。
 * 锁定两条契约：recoverable 仅在"工具持久化参数且调用成功"时为 true；
 * recoverable 时 persistedLocation 被盖到块 refId，供压缩骨架化直接读取。
 */
class ToolArgsRecoverRuleTest {

    private static final String ARGS_JSON = "{\"file_path\":\"/data/x.txt\",\"content\":\"...\"}";

    @Test
    @DisplayName("持久化工具且调用成功：recoverable=true 且 refId 盖入落盘位置")
    void stampsLocationWhenRecoverable() {
        ContextBlock block = argsBlock("call-1", "write", ARGS_JSON);
        ToolSupport ts = new StubToolSupport(true, "/data/x.txt");
        IngestContext ctx = ctx(ts, Set.of("call-1"));

        new ToolArgsRecoverRule().apply(block, ctx);

        assertThat(block.recoverable()).isTrue();
        assertThat(block.refId()).isEqualTo("/data/x.txt");
    }

    @Test
    @DisplayName("持久化工具但调用失败：recoverable=false，不盖位置")
    void notRecoverableWhenCallFailed() {
        ContextBlock block = argsBlock("call-1", "write", ARGS_JSON);
        ToolSupport ts = new StubToolSupport(true, "/data/x.txt");
        IngestContext ctx = ctx(ts, Set.of()); // call-1 不在成功集合

        new ToolArgsRecoverRule().apply(block, ctx);

        assertThat(block.recoverable()).isFalse();
        assertThat(block.refId()).isNull();
    }

    @Test
    @DisplayName("非持久化工具：recoverable=false，不盖位置")
    void notRecoverableForNonPersistingTool() {
        ContextBlock block = argsBlock("call-1", "read_file", ARGS_JSON);
        ToolSupport ts = new StubToolSupport(false, null);
        IngestContext ctx = ctx(ts, Set.of("call-1"));

        new ToolArgsRecoverRule().apply(block, ctx);

        assertThat(block.recoverable()).isFalse();
        assertThat(block.refId()).isNull();
    }

    @Test
    @DisplayName("持久化工具但位置为空：recoverable=true 但 refId 保持 null（压缩期降级为通用文案）")
    void recoverableButNoLocationLeavesRefIdNull() {
        ContextBlock block = argsBlock("call-1", "write", ARGS_JSON);
        ToolSupport ts = new StubToolSupport(true, null);
        IngestContext ctx = ctx(ts, Set.of("call-1"));

        new ToolArgsRecoverRule().apply(block, ctx);

        assertThat(block.recoverable()).isTrue();
        assertThat(block.refId()).isNull();
    }

    // ═══════════════ 工具方法 ═══════════════

    private static ContextBlock argsBlock(String callId, String toolName, String text) {
        return ContextBlock.builder()
                .id("b-" + callId).kind(BlockKind.TOOL_ARGS)
                .groupId("g-" + callId).ordinal(0)
                .toolName(toolName).toolCallId(callId)
                .text(text)
                .build();
    }

    private static IngestContext ctx(ToolSupport ts, Set<String> succeededIds) {
        return new IngestContext(new NoopStoreSupport(), ts, 1000, succeededIds);
    }

    /** 可编程 ToolSupport 桩。 */
    static final class StubToolSupport implements ToolSupport {
        final boolean persists;
        final String location;

        StubToolSupport(boolean persists, String location) {
            this.persists = persists;
            this.location = location;
        }

        @Override public boolean persistsArgs(String toolName) { return persists; }

        @Override public String persistedLocation(String toolName, String argumentsJson) {
            return location;
        }
    }

    /** 空操作 StoreSupport，本规则不消费它。 */
    static final class NoopStoreSupport implements StoreSupport {
        @Override public ArtifactRef save(String source, String content, String summary) {
            throw new UnsupportedOperationException();
        }
    }
}
