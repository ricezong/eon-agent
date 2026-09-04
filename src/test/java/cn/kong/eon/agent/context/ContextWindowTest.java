package cn.kong.eon.agent.context;

import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ContextWindow 的结构不变式特征测试：
 * 保护区按位置下标划定、pin 自动转移、removeBefore 与 repairPairing 配对修复。
 */
class ContextWindowTest {

    private static ContextBlock block(String id, BlockKind kind, String text) {
        return block(id, kind, text, "call-" + id);
    }

    private static ContextBlock block(String id, BlockKind kind, String text, String callId) {
        return ContextBlock.builder()
                .id(id).kind(kind).groupId("g-" + id).ordinal(0)
                .toolName(kind == BlockKind.USER_INPUT || kind == BlockKind.AI_TEXT ? null : "tool")
                .toolCallId(kind == BlockKind.USER_INPUT || kind == BlockKind.AI_TEXT ? null : callId)
                .text(text)
                .build();
    }

    // ═══════════════════ 保护区与 pin 转移 ═══════════════════

    @Test
    @DisplayName("pin 自动转移到最后一条用户输入：新 user 入窗时旧的取消 pin")
    void pinTransfersToLatestUserInput() {
        ContextWindow window = new ContextWindow();
        ContextBlock u1 = block("u1", BlockKind.USER_INPUT, "第一问");
        ContextBlock a1 = block("a1", BlockKind.AI_TEXT, "答一");
        ContextBlock u2 = block("u2", BlockKind.USER_INPUT, "第二问");

        window.addAll(List.of(u1, a1, u2));

        assertThat(u1.isPinned()).isFalse();
        assertThat(u2.isPinned()).isTrue();
        assertThat(window.lastUserIndex()).isEqualTo(2);
    }

    @Test
    @DisplayName("protectedFrom：以最后一条 user 块为锚点向上延伸 tailGuardBlocks 个块")
    void protectedFromUsesLastUserIndexAsAnchor() {
        ContextWindow window = new ContextWindow();
        // 5 个块，最后一条 user 在位置 2
        window.addAll(List.of(
                block("h1", BlockKind.AI_TEXT, "历史1"),
                block("h2", BlockKind.AI_TEXT, "历史2"),
                block("u", BlockKind.USER_INPUT, "当前问"),
                block("a", BlockKind.AI_TEXT, "当前答"),
                block("r", BlockKind.TOOL_RESULT, "结果")));

        // 锚点 = 2，向上延伸 2 块 → protectedFrom = 0
        assertThat(window.protectedFrom(2)).isEqualTo(0);
        // 向上延伸 1 块 → protectedFrom = 1
        assertThat(window.protectedFrom(1)).isEqualTo(1);
        // tailGuardBlocks 大于锚点位置 → 退化为 0
        assertThat(window.protectedFrom(99)).isEqualTo(0);
    }

    @Test
    @DisplayName("protectedFrom：窗口无 user 块时以 size 为锚点退化")
    void protectedFromFallsBackToSizeWhenNoUserBlock() {
        ContextWindow window = new ContextWindow();
        window.addAll(List.of(
                block("a1", BlockKind.AI_TEXT, "答1"),
                block("a2", BlockKind.AI_TEXT, "答2"),
                block("a3", BlockKind.AI_TEXT, "答3")));

        // 无 user 块：anchor = size = 3
        assertThat(window.protectedFrom(1)).isEqualTo(2);
        assertThat(window.protectedFrom(99)).isEqualTo(0);
    }

    // ═══════════════════ removeBefore ═══════════════════

    @Test
    @DisplayName("removeBefore 删除 protectedFrom 下标之前的全部块，pinned 块不动")
    void removeBeforeDropsEverythingBeforeProtectedFrom() {
        ContextWindow window = new ContextWindow();
        ContextBlock oldUser = block("u1", BlockKind.USER_INPUT, "老问题");
        ContextBlock oldAi = block("a1", BlockKind.AI_TEXT, "老回答");
        ContextBlock newUser = block("u9", BlockKind.USER_INPUT, "新问题");
        ContextBlock newAi = block("a9", BlockKind.AI_TEXT, "新回答");
        window.addAll(List.of(oldUser, oldAi, newUser, newAi));
        // lastUserIndex=2（newUser 位置），pin 转移到 newUser
        assertThat(newUser.isPinned()).isTrue();
        assertThat(oldUser.isPinned()).isFalse();

        // protectedFrom=2：删除下标 0、1 的块（oldUser、oldAi）
        assertThat(window.removeBefore(2)).isTrue();
        assertThat(window.blocks()).containsExactly(newUser, newAi);
        // pinned 块保留 pin 标记
        assertThat(newUser.isPinned()).isTrue();
        // 索引重建后仍指向 newUser
        assertThat(window.lastUserIndex()).isEqualTo(0);
        // protectedFrom=0：不删任何块
        assertThat(window.removeBefore(0)).isFalse();
    }

    // ═══════════════════ repairPairing ═══════════════════

    @Test
    @DisplayName("repairPairing 丢弃孤立结果、去重、为缺结果的调用补合成结果")
    void repairPairingFixesOrphansAndMissing() {
        ContextWindow window = new ContextWindow();
        ContextBlock call1 = block("c1", BlockKind.TOOL_ARGS, "{\"a\":1}", "call-A");
        ContextBlock result1 = block("r1", BlockKind.TOOL_RESULT, "结果一", "call-A");
        ContextBlock call2 = block("c2", BlockKind.TOOL_ARGS, "{\"b\":2}", "call-B");
        ContextBlock dupResult1 = block("r1d", BlockKind.TOOL_RESULT, "重复结果一", "call-A");
        ContextBlock orphanResult = block("rX", BlockKind.TOOL_RESULT, "孤立结果", "call-Z");
        window.addAll(List.of(call1, result1, call2, dupResult1, orphanResult));

        window.repairPairing();

        // call1/result1 配对完好；孤立与重复结果被丢弃；call2 补合成结果
        assertThat(window.blocks()).hasSize(4);
        assertThat(window.blocks().subList(0, 3)).containsExactly(call1, result1, call2);
        ContextBlock syn = window.blocks().get(3);
        assertThat(syn.kind()).isEqualTo(BlockKind.TOOL_RESULT);
        assertThat(syn.toolCallId()).isEqualTo("call-B");
        assertThat(syn.text()).contains("重新调用");
    }

    @Test
    @DisplayName("配对完好的窗口 repairPairing 无副作用")
    void repairPairingNoOpOnHealthy() {
        ContextWindow window = new ContextWindow();
        ContextBlock call = block("c1", BlockKind.TOOL_ARGS, "{}", "call-A");
        ContextBlock result = block("r1", BlockKind.TOOL_RESULT, "ok", "call-A");
        window.addAll(List.of(call, result));

        window.repairPairing();

        assertThat(window.blocks()).containsExactly(call, result);
    }
}
