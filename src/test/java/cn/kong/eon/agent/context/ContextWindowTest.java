package cn.kong.eon.agent.context;

import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.BlockProjector;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.agent.context.block.Retention;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文窗口测试。承接原 {@code PairingRepairerTest} 的断言，
 * 并补上新的按轮次保护区与逐字保留语义。
 */
class ContextWindowTest {

    private static ToolExecutionRequest req(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    private static void add(ContextWindow window, ChatMessage msg, int turn) {
        window.addAll(BlockProjector.explode(msg, "g" + window.size(), turn, null));
    }

    // ═══════════════════ 配对修复 ═══════════════════

    @Test
    void repairPairing_keepsPairedMessagesIntact() {
        ContextWindow window = new ContextWindow();
        add(window, UserMessage.from("read file"), 0);
        add(window, AiMessage.from("thinking", List.of(req("call_1", "read_file"))), 1);
        add(window, ToolExecutionResultMessage.from("call_1", "read_file", "content"), 2);

        window.repairPairing();
        List<ChatMessage> messages = window.toMessages();

        assertThat(messages).hasSize(3);
        assertThat(messages.get(1)).isInstanceOf(AiMessage.class);
        assertThat(messages.get(2)).isInstanceOf(ToolExecutionResultMessage.class);
    }

    @Test
    void repairPairing_dropsOrphanToolResult() {
        ContextWindow window = new ContextWindow();
        add(window, UserMessage.from("read file"), 0);
        add(window, AiMessage.from("thinking", List.of(req("call_1", "read_file"))), 1);
        add(window, ToolExecutionResultMessage.from("call_1", "read_file", "content"), 2);
        add(window, ToolExecutionResultMessage.from("call_ghost", "read_file", "orphan"), 3);

        window.repairPairing();
        List<ChatMessage> messages = window.toMessages();

        assertThat(messages).hasSize(3);
        assertThat(messages).noneMatch(m -> m instanceof ToolExecutionResultMessage trm
                && trm.id().equals("call_ghost"));
    }

    /**
     * 合成结果必须落在<b>独立消息组</b>里。
     * 若挂在原 AI 组下，组装时会被并入 AiMessage 而丢失——
     * AI 组只认 AI_TEXT 与 TOOL_ARGS 两种块。这是本次重构中修掉的一个真实缺陷。
     */
    @Test
    void repairPairing_insertsSyntheticResultForMissingToolResult() {
        ContextWindow window = new ContextWindow();
        add(window, UserMessage.from("read and write"), 0);
        add(window, AiMessage.from("thinking",
                List.of(req("call_1", "read_file"), req("call_2", "write_file"))), 1);
        add(window, ToolExecutionResultMessage.from("call_1", "read_file", "content"), 2);

        window.repairPairing();
        List<ChatMessage> messages = window.toMessages();

        ToolExecutionResultMessage synthetic = messages.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> (ToolExecutionResultMessage) m)
                .filter(trm -> trm.id().equals("call_2"))
                .findFirst()
                .orElseThrow();
        assertThat(synthetic.text()).contains("[合成]");

        // AI 消息里的两个调用都还在
        AiMessage ai = (AiMessage) messages.get(1);
        assertThat(ai.toolExecutionRequests()).hasSize(2);
    }

    /**
     * 重复的 tool_use id 只丢弃参数块，保留模型正文块——
     * 去重是为了满足"一个 tool_use id 只能出现一次"的 API 不变式，正文是无辜的。
     */
    @Test
    void repairPairing_dropsDuplicateArgs_butKeepsAiText() {
        ContextWindow window = new ContextWindow();
        add(window, AiMessage.from("thinking1", List.of(req("dup_1", "read_file"))), 0);
        add(window, ToolExecutionResultMessage.from("dup_1", "read_file", "result1"), 1);
        add(window, AiMessage.from("thinking2", List.of(req("dup_1", "read_file"))), 2);
        add(window, ToolExecutionResultMessage.from("dup_1", "read_file", "result2"), 3);

        window.repairPairing();
        List<ChatMessage> messages = window.toMessages();

        long withToolCalls = messages.stream()
                .filter(m -> m instanceof AiMessage am && am.hasToolExecutionRequests())
                .count();
        assertThat(withToolCalls).isEqualTo(1);

        // 正文不因去重而丢失
        assertThat(messages).anyMatch(m -> m instanceof AiMessage am && "thinking2".equals(am.text()));
    }

    @Test
    void repairPairing_passesThroughNonToolMessages() {
        ContextWindow window = new ContextWindow();
        add(window, SystemMessage.from("system prompt"), 0);
        add(window, UserMessage.from("user input"), 0);
        add(window, AiMessage.from("response without tools"), 0);

        window.repairPairing();
        List<ChatMessage> messages = window.toMessages();

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(2)).isInstanceOf(AiMessage.class);
    }

    // ═══════════════════ 按轮次的尾部保护区 ═══════════════════

    /**
     * 保护区按<b>轮次</b>判定，而不是按消息条数近似——
     * 原先的 {@code size - turns*2 - 2} 在一条消息拆出多块时会算错。
     */
    @Test
    void cutoffTurn_isBasedOnTurn_notMessageCount() {
        ContextWindow window = new ContextWindow();
        add(window, UserMessage.from("u1"), 0);
        add(window, UserMessage.from("u2"), 0);
        add(window, UserMessage.from("u3"), 0);
        add(window, UserMessage.from("u4"), 0);
        add(window, UserMessage.from("u5"), 5);

        assertThat(window.latestTurn()).isEqualTo(5);
        assertThat(window.cutoffTurn(1)).isEqualTo(4);
        assertThat(window.cutoffTurn(0)).isEqualTo(5);
    }

    // ═══════════════════ 逐字保留 ═══════════════════

    /**
     * 删除只作用于可压缩块，逐字块自动保留。
     * 过去 {@code subList(0, n).clear()} 会把早期用户消息连锅端掉。
     */
    @Test
    void removeBefore_keepsVerbatimBlocks() {
        ContextWindow window = new ContextWindow();
        add(window, UserMessage.from("early user request"), 0);      // VERBATIM
        add(window, ToolExecutionResultMessage.from("c1", "read_file", "big content"), 0);
        add(window, UserMessage.from("later user request"), 1);      // VERBATIM

        List<ContextBlock> removed = window.removeBefore(1);

        assertThat(removed).hasSize(1);
        assertThat(removed.get(0).kind()).isEqualTo(BlockKind.TOOL_RESULT);

        List<ContextBlock> kept = window.view();
        assertThat(kept).hasSize(2);
        assertThat(kept).allMatch(b -> b.retention() == Retention.VERBATIM);
        assertThat(kept).extracting(ContextBlock::text)
                .containsExactly("early user request", "later user request");
    }

    @Test
    void removeBefore_respectsTurnCutoff() {
        ContextWindow window = new ContextWindow();
        add(window, ToolExecutionResultMessage.from("c1", "read_file", "a"), 0);
        add(window, ToolExecutionResultMessage.from("c2", "read_file", "b"), 3);
        add(window, ToolExecutionResultMessage.from("c3", "read_file", "c"), 9);

        List<ContextBlock> removed = window.removeBefore(5);

        assertThat(removed).hasSize(2);
        assertThat(window.view()).extracting(ContextBlock::id).isNotEmpty();
        assertThat(window.view()).hasSize(1);
    }

    @Test
    void emptyWindow_isHandled() {
        ContextWindow window = new ContextWindow();
        assertThat(window.isEmpty()).isTrue();
        assertThat(window.latestTurn()).isZero();
        assertThat(window.toMessages()).isEmpty();
        assertThat(window.removeBefore(0)).isEmpty();
        window.repairPairing(); // 不应抛异常
    }
}
