package cn.kong.eon.agent.support;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolExecutionResult;
import cn.kong.eon.store.JsonlStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageFinalizerTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    private MessageFinalizer createFinalizer() {
        Path jsonl = tempDir.resolve("test.jsonl");
        return new MessageFinalizer(new JsonlStore(jsonl, mapper));
    }

    private JsonlStore createJsonlStore() {
        return new JsonlStore(tempDir.resolve("test.jsonl"), mapper);
    }

    @Test
    void finalizeAndAppend_withTextOnly_appendsAiMessage() {
        JsonlStore store = createJsonlStore();
        MessageFinalizer finalizer = new MessageFinalizer(store);
        SessionState state = SessionState.create("s1", "test");
        state.setLastAssistantText("hello from AI");
        state.setPendingToolCalls(null);
        state.setLastToolResults(null);
        TurnRecord rec = new TurnRecord();

        finalizer.finalizeAndAppend(rec, state);

        List<ChatMessage> snapshot = store.snapshot();
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) snapshot.get(0)).text()).isEqualTo("hello from AI");
    }

    @Test
    void finalizeAndAppend_withToolCalls_appendsAiMessageWithRequests() {
        JsonlStore store = createJsonlStore();
        MessageFinalizer finalizer = new MessageFinalizer(store);
        SessionState state = SessionState.create("s1", "test");
        state.setLastAssistantText("thinking");
        state.setPendingToolCalls(List.of(
                ToolExecutionRequest.builder().id("c1").name("read_file").arguments("{}").build()
        ));
        state.setLastToolResults(null);
        TurnRecord rec = new TurnRecord();

        finalizer.finalizeAndAppend(rec, state);

        List<ChatMessage> snapshot = store.snapshot();
        assertThat(snapshot).hasSize(1);
        AiMessage ai = (AiMessage) snapshot.get(0);
        assertThat(ai.text()).isEqualTo("thinking");
        assertThat(ai.hasToolExecutionRequests()).isTrue();
        assertThat(ai.toolExecutionRequests()).hasSize(1);
    }

    @Test
    void finalizeAndAppend_withToolResults_appendsToolResultMessages() {
        JsonlStore store = createJsonlStore();
        MessageFinalizer finalizer = new MessageFinalizer(store);
        SessionState state = SessionState.create("s1", "test");
        state.setLastAssistantText("thinking");
        state.setPendingToolCalls(List.of(
                ToolExecutionRequest.builder().id("c1").name("read_file").arguments("{}").build()
        ));
        state.setLastToolResults(List.of(
                new ToolExecutionResult("c1", "read_file", true, "[Tool result] read_file\ncontent")
        ));
        TurnRecord rec = new TurnRecord();

        finalizer.finalizeAndAppend(rec, state);

        List<ChatMessage> snapshot = store.snapshot();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0)).isInstanceOf(AiMessage.class);
        assertThat(snapshot.get(1)).isInstanceOf(ToolExecutionResultMessage.class);
        ToolExecutionResultMessage trm = (ToolExecutionResultMessage) snapshot.get(1);
        assertThat(trm.id()).isEqualTo("c1");
        assertThat(trm.toolName()).isEqualTo("read_file");
    }

    @Test
    void finalizeAndAppend_withMultipleToolResults_appendsAll() {
        JsonlStore store = createJsonlStore();
        MessageFinalizer finalizer = new MessageFinalizer(store);
        SessionState state = SessionState.create("s1", "test");
        state.setLastAssistantText("thinking");
        state.setPendingToolCalls(List.of(
                ToolExecutionRequest.builder().id("c1").name("read_file").arguments("{}").build(),
                ToolExecutionRequest.builder().id("c2").name("list_dir").arguments("{}").build()
        ));
        state.setLastToolResults(List.of(
                new ToolExecutionResult("c1", "read_file", true, "content1"),
                new ToolExecutionResult("c2", "list_dir", true, "content2")
        ));
        TurnRecord rec = new TurnRecord();

        finalizer.finalizeAndAppend(rec, state);

        List<ChatMessage> snapshot = store.snapshot();
        assertThat(snapshot).hasSize(3);
        assertThat(snapshot.get(0)).isInstanceOf(AiMessage.class);
        assertThat(((ToolExecutionResultMessage) snapshot.get(1)).id()).isEqualTo("c1");
        assertThat(((ToolExecutionResultMessage) snapshot.get(2)).id()).isEqualTo("c2");
    }

    @Test
    void finalizeAndAppend_clearsPendingState() {
        JsonlStore store = createJsonlStore();
        MessageFinalizer finalizer = new MessageFinalizer(store);
        SessionState state = SessionState.create("s1", "test");
        state.setLastAssistantText("thinking");
        state.setPendingToolCalls(List.of(
                ToolExecutionRequest.builder().id("c1").name("read_file").arguments("{}").build()
        ));
        state.setLastToolResults(List.of(
                new ToolExecutionResult("c1", "read_file", true, "content")
        ));
        state.getPendingNudges().add("nudge1");
        state.getFormatCorrections().add("correction1");
        TurnRecord rec = new TurnRecord();

        finalizer.finalizeAndAppend(rec, state);

        assertThat(state.getPendingToolCalls()).isNull();
        assertThat(state.getLastToolResults()).isNull();
        assertThat(state.getPendingNudges()).isEmpty();
        assertThat(state.getFormatCorrections()).isEmpty();
    }

    @Test
    void finalizeAndAppend_noTextNoCalls_appendsNothing() {
        JsonlStore store = createJsonlStore();
        MessageFinalizer finalizer = new MessageFinalizer(store);
        SessionState state = SessionState.create("s1", "test");
        state.setLastAssistantText("");
        state.setPendingToolCalls(null);
        state.setLastToolResults(null);
        TurnRecord rec = new TurnRecord();

        finalizer.finalizeAndAppend(rec, state);

        // No AiMessage should be appended (both text and calls are empty/null)
        // But tool results should still be handled
        assertThat(store.snapshot()).isEmpty();
    }

    @Test
    void finalizeAndAppend_callsOnly_noText_appendsAiWithoutText() {
        JsonlStore store = createJsonlStore();
        MessageFinalizer finalizer = new MessageFinalizer(store);
        SessionState state = SessionState.create("s1", "test");
        state.setLastAssistantText(null);
        state.setPendingToolCalls(List.of(
                ToolExecutionRequest.builder().id("c1").name("read_file").arguments("{}").build()
        ));
        TurnRecord rec = new TurnRecord();

        finalizer.finalizeAndAppend(rec, state);

        List<ChatMessage> snapshot = store.snapshot();
        assertThat(snapshot).hasSize(1);
        AiMessage ai = (AiMessage) snapshot.get(0);
        assertThat(ai.text()).isNull();
        assertThat(ai.hasToolExecutionRequests()).isTrue();
    }

    @Test
    void finalizeIfPending_withPending_executesFinalize() {
        JsonlStore store = createJsonlStore();
        MessageFinalizer finalizer = new MessageFinalizer(store);
        SessionState state = SessionState.create("s1", "test");
        state.setLastAssistantText("hello");
        state.setPendingToolCalls(null);
        TurnRecord rec = new TurnRecord();

        finalizer.finalizeIfPending(rec, state);

        assertThat(store.snapshot()).hasSize(1);
    }

    @Test
    void finalizeIfPending_withoutPending_doesNothing() {
        JsonlStore store = createJsonlStore();
        MessageFinalizer finalizer = new MessageFinalizer(store);
        SessionState state = SessionState.create("s1", "test");
        state.setLastAssistantText(null);
        state.setPendingToolCalls(null);
        state.setLastToolResults(null);
        TurnRecord rec = new TurnRecord();

        finalizer.finalizeIfPending(rec, state);

        assertThat(store.snapshot()).isEmpty();
    }

    @Test
    void finalizeIfPending_withToolResultsOnly_executesFinalize() {
        JsonlStore store = createJsonlStore();
        MessageFinalizer finalizer = new MessageFinalizer(store);
        SessionState state = SessionState.create("s1", "test");
        state.setLastAssistantText(null);
        state.setPendingToolCalls(null);
        state.setLastToolResults(List.of(
                new ToolExecutionResult("c1", "read_file", true, "content")
        ));
        TurnRecord rec = new TurnRecord();

        finalizer.finalizeIfPending(rec, state);

        // Should append the tool result message
        List<ChatMessage> snapshot = store.snapshot();
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0)).isInstanceOf(ToolExecutionResultMessage.class);
    }

    @Test
    void finalizeAndAppend_blankTextWithCalls_appendsAiWithCalls() {
        JsonlStore store = createJsonlStore();
        MessageFinalizer finalizer = new MessageFinalizer(store);
        SessionState state = SessionState.create("s1", "test");
        state.setLastAssistantText("   ");
        state.setPendingToolCalls(List.of(
                ToolExecutionRequest.builder().id("c1").name("read_file").arguments("{}").build()
        ));
        TurnRecord rec = new TurnRecord();

        finalizer.finalizeAndAppend(rec, state);

        List<ChatMessage> snapshot = store.snapshot();
        // hasCalls is true, so AiMessage should be appended (without text since blank)
        assertThat(snapshot).hasSize(1);
        AiMessage ai = (AiMessage) snapshot.get(0);
        assertThat(ai.hasToolExecutionRequests()).isTrue();
    }
}
