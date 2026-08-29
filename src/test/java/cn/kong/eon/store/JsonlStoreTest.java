package cn.kong.eon.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonlStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void append_andSnapshot_returnsSameMessages() {
        Path jsonl = tempDir.resolve("test.jsonl");
        JsonlStore store = new JsonlStore(jsonl, mapper);

        store.append(UserMessage.from("hello"));
        store.append(AiMessage.from("hi there"));

        List<ChatMessage> snapshot = store.snapshot();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0)).isInstanceOf(UserMessage.class);
        assertThat(snapshot.get(1)).isInstanceOf(AiMessage.class);
    }

    @Test
    void serializationRoundTrip_preservesAiMessageWithToolCalls() {
        Path jsonl = tempDir.resolve("roundtrip.jsonl");
        JsonlStore store = new JsonlStore(jsonl, mapper);

        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call_1").name("read_file").arguments("{\"path\":\"test.txt\"}").build();
        AiMessage aiMsg = AiMessage.from("thinking...", List.of(toolReq));

        store.append(UserMessage.from("read the file"));
        store.append(aiMsg);
        store.append(ToolExecutionResultMessage.from("call_1", "read_file", "file content"));

        List<ChatMessage> snapshot = store.snapshot();
        assertThat(snapshot).hasSize(3);

        AiMessage recovered = (AiMessage) snapshot.get(1);
        assertThat(recovered.text()).isEqualTo("thinking...");
        assertThat(recovered.hasToolExecutionRequests()).isTrue();
        assertThat(recovered.toolExecutionRequests()).hasSize(1);
        assertThat(recovered.toolExecutionRequests().get(0).name()).isEqualTo("read_file");

        ToolExecutionResultMessage trm = (ToolExecutionResultMessage) snapshot.get(2);
        assertThat(trm.id()).isEqualTo("call_1");
        assertThat(trm.toolName()).isEqualTo("read_file");
        assertThat(trm.text()).isEqualTo("file content");
    }

    @Test
    void snapshot_isDefensiveCopy() {
        Path jsonl = tempDir.resolve("defensive.jsonl");
        JsonlStore store = new JsonlStore(jsonl, mapper);

        store.append(UserMessage.from("msg1"));
        List<ChatMessage> snap1 = store.snapshot();
        snap1.clear(); // modify the copy

        List<ChatMessage> snap2 = store.snapshot();
        assertThat(snap2).hasSize(1);
    }

    @Test
    void append_persistsAcrossInstances() {
        Path jsonl = tempDir.resolve("persist.jsonl");
        JsonlStore store1 = new JsonlStore(jsonl, mapper);
        store1.append(UserMessage.from("persisted"));

        JsonlStore store2 = new JsonlStore(jsonl, mapper);
        List<ChatMessage> snapshot = store2.snapshot();
        assertThat(snapshot).hasSize(1);
        assertThat(((UserMessage) snapshot.get(0)).singleText()).isEqualTo("persisted");
    }

    @Test
    void replaceAll_updatesViewWithoutTouchingDiskLedger() throws IOException {
        Path jsonl = tempDir.resolve("replace.jsonl");
        JsonlStore store = new JsonlStore(jsonl, mapper);

        store.append(UserMessage.from("msg1"));
        store.append(AiMessage.from("reply1"));
        store.append(UserMessage.from("msg2"));

        store.replaceAll(List.of(SystemMessage.from("compact summary"), UserMessage.from("msg2")));

        List<ChatMessage> snapshot = store.snapshot();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((UserMessage) snapshot.get(1)).singleText()).isEqualTo("msg2");

        // 磁盘账本不受影响：仍为追加时的 3 行
        List<String> lines = Files.readAllLines(jsonl);
        assertThat(lines).hasSize(3);
    }

    @Test
    void replaceAll_thenAppend_continuesWorking() {
        Path jsonl = tempDir.resolve("replace-append.jsonl");
        JsonlStore store = new JsonlStore(jsonl, mapper);

        store.append(UserMessage.from("old"));
        store.replaceAll(List.of(SystemMessage.from("compact")));
        store.append(UserMessage.from("new"));

        List<ChatMessage> snapshot = store.snapshot();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((UserMessage) snapshot.get(1)).singleText()).isEqualTo("new");
    }
}
