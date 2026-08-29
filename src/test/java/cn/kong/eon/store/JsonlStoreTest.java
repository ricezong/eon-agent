package cn.kong.eon.store;

import cn.kong.eon.agent.context.pipeline.ArtifactSpillRule;
import cn.kong.eon.agent.context.pipeline.ContextPipeline;
import cn.kong.eon.agent.context.pipeline.ToolResultFormatRule;
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

        store.append(UserMessage.from("hello"), 0);
        store.append(AiMessage.from("hi there"), 0);

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

        store.append(UserMessage.from("read the file"), 0);
        store.append(aiMsg, 0);
        store.append(ToolExecutionResultMessage.from("call_1", "read_file", "file content"), 0);

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

        store.append(UserMessage.from("msg1"), 0);
        List<ChatMessage> snap1 = store.snapshot();
        snap1.clear(); // modify the copy

        List<ChatMessage> snap2 = store.snapshot();
        assertThat(snap2).hasSize(1);
    }

    @Test
    void append_persistsAcrossInstances() {
        Path jsonl = tempDir.resolve("persist.jsonl");
        JsonlStore store1 = new JsonlStore(jsonl, mapper);
        store1.append(UserMessage.from("persisted"), 0);

        JsonlStore store2 = new JsonlStore(jsonl, mapper);
        List<ChatMessage> snapshot = store2.snapshot();
        assertThat(snapshot).hasSize(1);
        assertThat(((UserMessage) snapshot.get(0)).singleText()).isEqualTo("persisted");
    }

    @Test
    void replaceAll_updatesViewWithoutTouchingDiskLedger() throws IOException {
        Path jsonl = tempDir.resolve("replace.jsonl");
        JsonlStore store = new JsonlStore(jsonl, mapper);

        store.append(UserMessage.from("msg1"), 0);
        store.append(AiMessage.from("reply1"), 0);
        store.append(UserMessage.from("msg2"), 0);

        store.replaceAll(List.of(SystemMessage.from("compact summary"), UserMessage.from("msg2")));

        List<ChatMessage> snapshot = store.snapshot();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((UserMessage) snapshot.get(1)).singleText()).isEqualTo("msg2");

        // 磁盘账本不受影响：仍为追加时的 3 行
        List<String> lines = Files.readAllLines(jsonl);
        assertThat(lines).hasSize(3);
    }

    /**
     * 回归：账本必须记录"入站处置后"的形态，而不是工具返回的原文。
     * <p>
     * 渲染器从工具层上移进入站管线后，若账本仍写原文，
     * 一条几万字符的工具结果会让单条 JSONL 记录膨胀成十几万字符的一行，
     * "一条消息一行"名存实亡。
     */
    @Test
    void append_withPipeline_ledgerStoresIngestedFormNotRaw() throws IOException {
        Path jsonl = tempDir.resolve("spill.jsonl");
        JsonlStore store = new JsonlStore(jsonl, mapper);

        ArtifactStore artifacts = new ArtifactStore(tempDir.resolve("artifacts"));
        store.setPipeline(new ContextPipeline(
                List.of(new ArtifactSpillRule(), new ToolResultFormatRule()),
                null, artifacts, null, mapper,
                4000,     // snipKeepChars → 落盘阈值 12000、摘要 8000
                2000));

        store.append(ToolExecutionResultMessage.from(
                "call_1", "reader_chapters", "x".repeat(50_000)), 1);

        List<String> lines = Files.readAllLines(jsonl);

        // 一条结果一行：不因内容含换行而裂成多行
        assertThat(lines).hasSize(1);
        // 不存 5 万字符原文：只留摘要 + artifact 引用
        assertThat(lines.get(0).length()).isLessThan(20_000);
        assertThat(lines.get(0)).contains("artifact://");
        // 原文完整保存在 artifact 里，未丢失
        assertThat(artifacts.readContent("art_001")).hasSize(50_000);
    }

    @Test
    void append_withoutPipeline_ledgerStoresOriginalMessage() throws IOException {
        Path jsonl = tempDir.resolve("no-pipeline.jsonl");
        JsonlStore store = new JsonlStore(jsonl, mapper);

        store.append(ToolExecutionResultMessage.from("call_1", "read_file", "raw content"), 0);

        List<String> lines = Files.readAllLines(jsonl);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("raw content");
    }

    @Test
    void replaceAll_thenAppend_continuesWorking() {
        Path jsonl = tempDir.resolve("replace-append.jsonl");
        JsonlStore store = new JsonlStore(jsonl, mapper);

        store.append(UserMessage.from("old"), 0);
        store.replaceAll(List.of(SystemMessage.from("compact")));
        store.append(UserMessage.from("new"), 0);

        List<ChatMessage> snapshot = store.snapshot();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((UserMessage) snapshot.get(1)).singleText()).isEqualTo("new");
    }
}
