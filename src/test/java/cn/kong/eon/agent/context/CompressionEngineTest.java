package cn.kong.eon.agent.context;

import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.model.CompressionState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompressionEngineTest {

    private CompressionEngine createEngine() {
        return new CompressionEngine(0.5, 0.75, 0.95, 100, 50000, 2000, null, "/test/transcript.jsonl");
    }

    private ToolExecutionResultMessage createToolResult(String id, String toolName, String content) {
        return ToolExecutionResultMessage.from(id, toolName, content);
    }

    /**
     * Build a message list with enough messages to have some outside the tail guard.
     * tailGuardTurns=0 -> tailStart = max(0, count - 0*2 - 2) = max(0, count-2).
     * So messages at indices 0..count-3 are eligible for compression.
     */
    private List<ChatMessage> buildMessagesForSnip(int count, String longContent) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(createToolResult("c" + i, "read_file", longContent));
        }
        return messages;
    }

    @Test
    void compress_belowSnipThreshold_doesNothing() {
        CompressionEngine engine = createEngine();
        List<ChatMessage> messages = new ArrayList<>(List.of(
                UserMessage.from("hello"),
                AiMessage.from("hi"),
                createToolResult("c1", "read_file", "short content")
        ));
        CompressionState state = new CompressionState();

        List<ChatMessage> result = engine.compress(messages, state, 0.3, 2);

        assertThat(result).hasSize(3);
        assertThat(state.getSnippedIds()).isEmpty();
    }

    @Test
    void compress_atSnipThreshold_snipsLongToolResults() {
        CompressionEngine engine = createEngine();
        String longContent = "x".repeat(500);
        // 10 messages, tailGuardTurns=0 -> tailStart = max(0, 10-2) = 8
        // indices 0..7 are eligible for snip
        List<ChatMessage> messages = new ArrayList<>(buildMessagesForSnip(10, longContent));
        CompressionState state = new CompressionState();

        List<ChatMessage> result = engine.compress(messages, state, 0.5, 0);

        // c0 through c7 should be snipped (8 items)
        assertThat(state.isSnipped("c0")).isTrue();
        assertThat(state.isSnipped("c7")).isTrue();
        // c8 and c9 are in tail guard, not snipped
        assertThat(state.isSnipped("c8")).isFalse();
        assertThat(state.isSnipped("c9")).isFalse();

        // Verify snipped content is shorter
        ToolExecutionResultMessage snipped = (ToolExecutionResultMessage) result.get(0);
        assertThat(snipped.text().length()).isLessThan(longContent.length());
        assertThat(snipped.text()).contains("[中间内容已省略");
    }

    @Test
    void compress_tailGuardProtectsRecentMessages() {
        CompressionEngine engine = createEngine();
        String longContent = "x".repeat(500);
        // 10 messages, tailGuardTurns=3 -> tailStart = max(0, 10-6-2) = 2
        List<ChatMessage> messages = new ArrayList<>(buildMessagesForSnip(10, longContent));
        CompressionState state = new CompressionState();

        engine.compress(messages, state, 0.5, 3);
        // c0, c1 should be snipped (before tailStart=2)
        assertThat(state.isSnipped("c0")).isTrue();
        assertThat(state.isSnipped("c1")).isTrue();
        // c2+ are in tail guard
        assertThat(state.isSnipped("c2")).isFalse();

        // With tailGuardTurns=4 -> tailStart = max(0, 10-8-2) = 0, all protected
        state = new CompressionState();
        messages = new ArrayList<>(buildMessagesForSnip(10, longContent));
        engine.compress(messages, state, 0.5, 4);
        assertThat(state.getSnippedIds()).isEmpty();
    }

    @Test
    void compress_atPruneThreshold_prunesToolResults() {
        CompressionEngine engine = createEngine();
        String content = "x".repeat(50);
        List<ChatMessage> messages = new ArrayList<>(buildMessagesForSnip(10, content));
        CompressionState state = new CompressionState();

        engine.compress(messages, state, 0.75, 0);

        // c0 through c7 should be pruned (replaced with placeholder)
        assertThat(state.isPruned("c0")).isTrue();
        assertThat(state.isPruned("c7")).isTrue();
        // Prune implies Snip
        assertThat(state.isSnipped("c0")).isTrue();
        // c8 and c9 are in tail guard
        assertThat(state.isPruned("c8")).isFalse();
    }

    @Test
    void compressByTurnCount_belowSnipThreshold_doesNothing() {
        CompressionEngine engine = createEngine();
        List<ChatMessage> messages = new ArrayList<>(List.of(
                createToolResult("c1", "read_file", "x".repeat(500))
        ));
        CompressionState state = new CompressionState();

        List<ChatMessage> result = engine.compressByTurnCount(messages, state, 0.3, 2);
        assertThat(result).hasSize(1);
        assertThat(state.getSnippedIds()).isEmpty();
    }

    @Test
    void compressByTurnCount_atSnipThreshold_snipsOnly() {
        CompressionEngine engine = createEngine();
        String longContent = "x".repeat(500);
        List<ChatMessage> messages = new ArrayList<>(buildMessagesForSnip(10, longContent));
        CompressionState state = new CompressionState();

        engine.compressByTurnCount(messages, state, 0.5, 0);
        // Should snip but not prune
        assertThat(state.isSnipped("c0")).isTrue();
        assertThat(state.isPruned("c0")).isFalse();
    }

    @Test
    void compress_idempotent_alreadySnippedSkipsMessage() {
        CompressionEngine engine = createEngine();
        String longContent = "x".repeat(500);
        List<ChatMessage> messages = new ArrayList<>(List.of(
                createToolResult("c1", "read_file", longContent),
                createToolResult("c2", "read_file", longContent)
        ));
        CompressionState state = new CompressionState();
        state.markSnipped("c1");

        engine.compress(messages, state, 0.5, 0);
        // Already snipped, should not change content
        ToolExecutionResultMessage result = (ToolExecutionResultMessage) messages.get(0);
        assertThat(result.text()).isEqualTo(longContent);
    }

    @Test
    void compress_preservesArtifactReferenceInSnip() {
        CompressionEngine engine = createEngine();
        String content = "artifact://art_001\n" + "x".repeat(500);
        List<ChatMessage> messages = new ArrayList<>(buildMessagesForSnip(10, content));
        CompressionState state = new CompressionState();

        engine.compress(messages, state, 0.5, 0);
        ToolExecutionResultMessage result = (ToolExecutionResultMessage) messages.get(0);
        assertThat(result.text()).contains("art_001");
        assertThat(result.text()).contains("[");
    }
}
