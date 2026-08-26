package cn.kong.eon.agent.context;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PairingRepairerTest {

    private final PairingRepairer repairer = new PairingRepairer();

    @Test
    void repair_keepsPairedMessagesIntact() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call_1").name("read_file").arguments("{}").build();

        List<ChatMessage> messages = List.of(
                UserMessage.from("read file"),
                AiMessage.from("thinking", List.of(req)),
                ToolExecutionResultMessage.from("call_1", "read_file", "content")
        );

        List<ChatMessage> result = repairer.repair(messages);

        assertThat(result).hasSize(3);
        assertThat(result.get(1)).isInstanceOf(AiMessage.class);
        assertThat(result.get(2)).isInstanceOf(ToolExecutionResultMessage.class);
    }

    @Test
    void repair_dropsOrphanToolResult() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call_1").name("read_file").arguments("{}").build();
        ToolExecutionRequest ghostReq = ToolExecutionRequest.builder()
                .id("call_ghost").name("read_file").arguments("{}").build();

        List<ChatMessage> messages = List.of(
                UserMessage.from("read file"),
                AiMessage.from("thinking", List.of(req)),
                ToolExecutionResultMessage.from("call_1", "read_file", "content"),
                ToolExecutionResultMessage.from("call_ghost", "read_file", "orphan result")
        );

        List<ChatMessage> result = repairer.repair(messages);

        // orphan result should be dropped (3 messages remain)
        assertThat(result).hasSize(3);
        // no reference to ghost result
        assertThat(result).noneMatch(m -> m instanceof ToolExecutionResultMessage trm
                && trm.id().equals("call_ghost"));
    }

    @Test
    void repair_insertsSyntheticErrorForMissingToolResult() {
        ToolExecutionRequest req1 = ToolExecutionRequest.builder()
                .id("call_1").name("read_file").arguments("{}").build();
        ToolExecutionRequest req2 = ToolExecutionRequest.builder()
                .id("call_2").name("write_file").arguments("{}").build();

        List<ChatMessage> messages = List.of(
                UserMessage.from("read and write"),
                AiMessage.from("thinking", List.of(req1, req2)),
                ToolExecutionResultMessage.from("call_1", "read_file", "content")
                // call_2 result is missing
        );

        List<ChatMessage> result = repairer.repair(messages);

        // PairingRepairer inserts synthetic for missing results right after the AiMessage,
        // then the existing ToolResult follows.
        // So: User(0), Ai(1), synthetic_call_2(2), result_call_1(3)
        assertThat(result).hasSize(4);
        // Find the synthetic result for call_2
        ToolExecutionResultMessage synthetic = result.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> (ToolExecutionResultMessage) m)
                .filter(trm -> trm.id().equals("call_2"))
                .findFirst()
                .orElseThrow();
        assertThat(synthetic.id()).isEqualTo("call_2");
        assertThat(synthetic.text()).contains("[SYNTHETIC]");
    }

    @Test
    void repair_deduplicatesDuplicateToolUseIds() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("dup_1").name("read_file").arguments("{}").build();

        List<ChatMessage> messages = List.of(
                AiMessage.from("thinking1", List.of(req)),
                ToolExecutionResultMessage.from("dup_1", "read_file", "result1"),
                AiMessage.from("thinking2", List.of(req)), // duplicate
                ToolExecutionResultMessage.from("dup_1", "read_file", "result2")
        );

        List<ChatMessage> result = repairer.repair(messages);

        // second AiMessage with dup_1 should be dropped
        long aiCount = result.stream().filter(m -> m instanceof AiMessage).count();
        assertThat(aiCount).isEqualTo(1);
    }

    @Test
    void repair_emptyListReturnsEmpty() {
        List<ChatMessage> result = repairer.repair(List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void repair_nullReturnsNull() {
        assertThat(repairer.repair(null)).isNull();
    }

    @Test
    void repair_passesThroughNonToolMessages() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("system prompt"),
                UserMessage.from("user input"),
                AiMessage.from("response without tools")
        );

        List<ChatMessage> result = repairer.repair(messages);

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(result.get(2)).isInstanceOf(AiMessage.class);
    }
}
