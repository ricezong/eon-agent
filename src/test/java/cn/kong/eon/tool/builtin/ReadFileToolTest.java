package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.ArtifactRef;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.store.ArtifactStore;
import cn.kong.eon.store.CheckpointStore;
import cn.kong.eon.store.JsonlStore;
import cn.kong.eon.store.MemoryStore;
import cn.kong.eon.store.TodoStore;
import cn.kong.eon.tool.PathResolver;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReadFileToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReadFileTool tool = new ReadFileTool();

    private SessionState state;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        state = SessionState.create("test-session", "input");
        context = new ToolContext(
                new TodoStore(),
                new ArtifactStore(tempDir.resolve("artifacts")),
                new MemoryStore(tempDir.resolve("memory"), mapper),
                new JsonlStore(tempDir.resolve("transcript.jsonl"), mapper),
                new CheckpointStore(tempDir.resolve("checkpoints"), mapper),
                new PathResolver(tempDir.toString(), false));
    }

    private Map<String, Object> args(String target, Integer offset, Integer limit) {
        Map<String, Object> args = new HashMap<>();
        args.put("target_file", target);
        if (offset != null) args.put("offset", offset);
        if (limit != null) args.put("limit", limit);
        return args;
    }

    /** 构造 5 行、每行 3000 字符的超长内容（总长超外置阈值 12000）。 */
    private ArtifactRef saveLargeArtifact() {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            lines.add("line" + i + "-" + "x".repeat(3000));
        }
        String content = String.join("\n", lines);
        return context.artifactStore().save("web_search", content, "test artifact");
    }

    @Test
    void artifact_paging_returnsOnlyRequestedWindow() {
        ArtifactRef ref = saveLargeArtifact();

        ToolOutcome outcome = tool.execute(
                args("artifact://" + ref.getRefId(), 2, 2), state, context);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.content()).startsWith("line2-");
        assertThat(outcome.content()).contains("line3-");
        assertThat(outcome.content()).doesNotContain("line4-");
        assertThat(outcome.content()).contains("(共 5 行，已显示第 2-3 行，可调整 offset/limit 继续读取)");
        // 分页后结果远小于全文，不会再触发外置落盘
        assertThat(outcome.content().length()).isLessThan(12000);
    }

    @Test
    void artifact_offsetBeyondTotalLines_returnsHint() {
        ArtifactRef ref = saveLargeArtifact();

        ToolOutcome outcome = tool.execute(
                args("artifact://" + ref.getRefId(), 99, null), state, context);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.content()).contains("超出总行数");
    }

    @Test
    void artifact_unknownRef_fails() {
        ToolOutcome outcome = tool.execute(
                args("artifact://art_999", null, null), state, context);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.content()).contains("art_999");
    }

    @Test
    void plainFile_usesSamePaging() throws IOException {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, String.join("\n", "l1", "l2", "l3", "l4"));

        ToolOutcome outcome = tool.execute(args("sample.txt", 2, 2), state, context);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.content()).startsWith("l2");
        assertThat(outcome.content()).contains("l3");
        assertThat(outcome.content()).doesNotContain("l4");
        assertThat(outcome.content()).contains("(共 4 行，已显示第 2-3 行，可调整 offset/limit 继续读取)");
    }

    @Test
    void plainFile_fullReadWithinLimit_hasNoFooter() throws IOException {
        Path file = tempDir.resolve("short.txt");
        Files.writeString(file, "a\nb\nc");

        ToolOutcome outcome = tool.execute(args("short.txt", null, null), state, context);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.content()).isEqualTo("a\nb\nc");
    }
}
