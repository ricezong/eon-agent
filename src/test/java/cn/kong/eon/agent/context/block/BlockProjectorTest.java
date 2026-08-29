package cn.kong.eon.agent.context.block;

import cn.kong.eon.agent.context.ToolSupport;
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
 * 投射层测试：{@code ChatMessage} ⇄ {@code List<ContextBlock>} 双向无损。
 * <p>
 * 这是 A1 方案（ContentBlock + 投射层）的边界契约：
 * 领域操作全在块上做，只在进出 LLM 的两端各投射一次。
 */
class BlockProjectorTest {

    private static final ToolSupport PERSISTING = new ToolSupport() {
        @Override
        public boolean persistsArguments(String toolName) {
            return "write_file".equals(toolName);
        }

        @Override
        public String summarizeArgs(String toolName, String argumentsJson) {
            return null;
        }
    };

    // ═══════════════════ 爆炸 ═══════════════════

    @Test
    void explode_systemMessage_becomesSingleVerbatimBlock() {
        List<ContextBlock> blocks = BlockProjector.explode(SystemMessage.from("sys"), "g0", 0, null);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).kind()).isEqualTo(BlockKind.SYSTEM);
        assertThat(blocks.get(0).retention()).isEqualTo(Retention.VERBATIM);
    }

    @Test
    void explode_userMessage_becomesSingleVerbatimBlock() {
        List<ContextBlock> blocks = BlockProjector.explode(UserMessage.from("hi"), "g0", 0, null);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).kind()).isEqualTo(BlockKind.USER_INPUT);
        assertThat(blocks.get(0).retention()).isEqualTo(Retention.VERBATIM);
    }

    /**
     * 一条 AiMessage = 正文 + N 个调用 = N+1 个块。
     * 把"消息"拆成"块"，才能对工具参数和模型正文分别施加策略。
     */
    @Test
    void explode_aiMessage_splitsTextAndEachToolCall() {
        AiMessage msg = AiMessage.from("thinking", List.of(
                ToolExecutionRequest.builder().id("c1").name("read_file").arguments("{}").build(),
                ToolExecutionRequest.builder().id("c2").name("write_file").arguments("{}").build()));

        List<ContextBlock> blocks = BlockProjector.explode(msg, "g0", 3, null);

        assertThat(blocks).hasSize(3);
        assertThat(blocks).extracting(ContextBlock::kind)
                .containsExactly(BlockKind.AI_TEXT, BlockKind.TOOL_ARGS, BlockKind.TOOL_ARGS);
        assertThat(blocks).extracting(ContextBlock::ordinal).containsExactly(0, 1, 2);
        assertThat(blocks).allMatch(b -> b.turn() == 3);
        assertThat(blocks).allMatch(b -> "g0".equals(b.groupId()));
    }

    @Test
    void explode_aiMessageWithoutText_omitsEmptyTextBlock() {
        AiMessage msg = AiMessage.from(List.of(
                ToolExecutionRequest.builder().id("c1").name("read_file").arguments("{}").build()));

        List<ContextBlock> blocks = BlockProjector.explode(msg, "g0", 0, null);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).kind()).isEqualTo(BlockKind.TOOL_ARGS);
    }

    /**
     * 保留策略由工具元数据决定，而不是靠调用处的 instanceof 判断。
     * 工具声明会把参数落盘 → 参数块是 OFFLOADABLE（可无损卸载）。
     */
    @Test
    void explode_marksArgsOffloadable_whenToolPersistsArguments() {
        AiMessage msg = AiMessage.from(List.of(
                ToolExecutionRequest.builder().id("c1").name("write_file").arguments("{}").build(),
                ToolExecutionRequest.builder().id("c2").name("read_file").arguments("{}").build()));

        List<ContextBlock> blocks = BlockProjector.explode(msg, "g0", 0, PERSISTING);

        assertThat(blocks.get(0).retention()).isEqualTo(Retention.OFFLOADABLE);   // write_file
        assertThat(blocks.get(1).retention()).isEqualTo(Retention.COMPRESSIBLE);  // read_file
    }

    @Test
    void explode_toolResult_becomesSingleCompressibleBlock() {
        List<ContextBlock> blocks = BlockProjector.explode(
                ToolExecutionResultMessage.from("c1", "read_file", "content"), "g0", 2, null);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).kind()).isEqualTo(BlockKind.TOOL_RESULT);
        assertThat(blocks.get(0).retention()).isEqualTo(Retention.COMPRESSIBLE);
        assertThat(blocks.get(0).toolCallId()).isEqualTo("c1");
        assertThat(blocks.get(0).toolName()).isEqualTo("read_file");
    }

    // ═══════════════════ 组装 ═══════════════════

    @Test
    void assemble_roundTripsAiMessageWithToolCalls() {
        AiMessage original = AiMessage.from("thinking...", List.of(
                ToolExecutionRequest.builder().id("c1").name("read_file")
                        .arguments("{\"path\":\"test.txt\"}").build()));

        List<ContextBlock> blocks = BlockProjector.explode(original, "g0", 0, null);
        List<ChatMessage> messages = BlockProjector.assemble(blocks);

        assertThat(messages).hasSize(1);
        AiMessage recovered = (AiMessage) messages.get(0);
        assertThat(recovered.text()).isEqualTo("thinking...");
        assertThat(recovered.toolExecutionRequests()).hasSize(1);
        assertThat(recovered.toolExecutionRequests().get(0).name()).isEqualTo("read_file");
        assertThat(recovered.toolExecutionRequests().get(0).arguments())
                .isEqualTo("{\"path\":\"test.txt\"}");
    }

    @Test
    void assemble_mergesBlocksByGroupInOrdinalOrder() {
        List<ContextBlock> blocks = new java.util.ArrayList<>();
        blocks.addAll(BlockProjector.explode(UserMessage.from("first"), "g1", 0, null));
        blocks.addAll(BlockProjector.explode(UserMessage.from("second"), "g2", 1, null));

        // 故意打乱顺序，组装应按 groupId 首次出现顺序 + 组内 ordinal 还原
        java.util.Collections.reverse(blocks);

        List<ChatMessage> messages = BlockProjector.assemble(blocks);

        assertThat(messages).hasSize(2);
        assertThat(((UserMessage) messages.get(0)).singleText()).isEqualTo("second");
        assertThat(((UserMessage) messages.get(1)).singleText()).isEqualTo("first");
    }

    @Test
    void assemble_roundTripsToolResult() {
        ToolExecutionResultMessage original =
                ToolExecutionResultMessage.from("c1", "read_file", "file content");

        List<ChatMessage> messages = BlockProjector.assemble(
                BlockProjector.explode(original, "g0", 0, null));

        assertThat(messages).hasSize(1);
        ToolExecutionResultMessage recovered = (ToolExecutionResultMessage) messages.get(0);
        assertThat(recovered.id()).isEqualTo("c1");
        assertThat(recovered.toolName()).isEqualTo("read_file");
        assertThat(recovered.text()).isEqualTo("file content");
    }

    @Test
    void assemble_emptyBlocks_returnsEmptyMessages() {
        assertThat(BlockProjector.assemble(List.of())).isEmpty();
    }

    @Test
    void explodeAll_assignsOneGroupPerMessage() {
        List<ChatMessage> messages = List.of(
                UserMessage.from("a"),
                AiMessage.from("b"),
                SystemMessage.from("c"));

        List<ContextBlock> blocks = BlockProjector.explodeAll(messages, 0, null);

        assertThat(blocks).hasSize(3);
        assertThat(blocks).extracting(ContextBlock::groupId).doesNotHaveDuplicates();
    }

    @Test
    void explodeAll_nullMessages_returnsEmpty() {
        assertThat(BlockProjector.explodeAll(null, 0, null)).isEmpty();
    }
}
