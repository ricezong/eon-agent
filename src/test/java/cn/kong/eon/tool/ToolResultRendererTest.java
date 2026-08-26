package cn.kong.eon.tool;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultRendererTest {

    @TempDir
    Path tempDir;

    private ToolResultRenderer createRenderer() {
        return new ToolResultRenderer(new ArtifactStore(tempDir));
    }

    @Test
    void render_smallResult_directOutputWithoutArtifact() {
        ToolResultRenderer renderer = createRenderer();
        ToolOutcome outcome = ToolOutcome.success("hello world");
        SessionState state = SessionState.create("s1", "test");

        String result = renderer.render("read_file", outcome, state);

        assertThat(result).contains("[Tool result] read_file");
        assertThat(result).contains("成功");
        assertThat(result).contains("hello world");
        assertThat(result).contains("11 字符");
        assertThat(result).doesNotContain("artifact://");
        assertThat(result).doesNotContain("截断提示");
    }

    @Test
    void render_largeResult_truncatedAndSavedAsArtifact() {
        ToolResultRenderer renderer = createRenderer();
        String largeContent = "x".repeat(4000);
        ToolOutcome outcome = ToolOutcome.success(largeContent);
        SessionState state = SessionState.create("s1", "test");

        String result = renderer.render("read_file", outcome, state);

        assertThat(result).contains("[Tool result] read_file");
        assertThat(result).contains("成功");
        assertThat(result).contains("截断提示");
        assertThat(result).contains("4000 字符");
        assertThat(result).contains("artifact://");
        // The display content should be shorter than the original
        assertThat(result.length()).isLessThan(largeContent.length());
    }

    @Test
    void render_largeResult_headTailSummary() {
        ToolResultRenderer renderer = createRenderer();
        // Content longer than SUMMARY_HEAD(700) + SUMMARY_TAIL(300) = 1000
        // Content must exceed ARTIFACT_THRESHOLD(3000) to trigger truncation
        // head fills first 700 chars completely (marker + padding)
        // tail fills last 300 chars completely (padding + marker)
        String head = "HEAD_MARKER_" + "h".repeat(800);
        String mid = "M".repeat(2000);
        String tail = "t".repeat(300) + "TAIL_MARKER_";
        String largeContent = head + mid + tail;
        ToolOutcome outcome = ToolOutcome.success(largeContent);
        SessionState state = SessionState.create("s1", "test");

        String result = renderer.render("read_file", outcome, state);

        assertThat(result).contains("HEAD_MARKER_");
        assertThat(result).contains("TAIL_MARKER_");
        assertThat(result).contains("中间内容已省略");
        assertThat(result).doesNotContain("MMMMMM"); // middle part should not be present
    }

    @Test
    void render_failureResult_showsFailureStatus() {
        ToolResultRenderer renderer = createRenderer();
        ToolOutcome outcome = ToolOutcome.failure("file not found");
        SessionState state = SessionState.create("s1", "test");

        String result = renderer.render("read_file", outcome, state);

        assertThat(result).contains("[Tool result] read_file");
        assertThat(result).contains("失败");
        assertThat(result).contains("file not found");
        assertThat(result).doesNotContain("artifact://");
    }

    @Test
    void render_largeFailureResult_alsoTruncated() {
        ToolResultRenderer renderer = createRenderer();
        String largeError = "E".repeat(4000);
        ToolOutcome outcome = ToolOutcome.failure(largeError);
        SessionState state = SessionState.create("s1", "test");

        String result = renderer.render("write_file", outcome, state);

        // Even failure results get truncated if large
        assertThat(result).contains("截断提示");
        assertThat(result).contains("artifact://");
    }

    @Test
    void render_boundarySize_notTruncated() {
        ToolResultRenderer renderer = createRenderer();
        // Exactly at threshold (3000), should NOT be truncated (strictly greater than)
        String content = "x".repeat(3000);
        ToolOutcome outcome = ToolOutcome.success(content);
        SessionState state = SessionState.create("s1", "test");

        String result = renderer.render("read_file", outcome, state);

        assertThat(result).doesNotContain("截断提示");
        assertThat(result).doesNotContain("artifact://");
        assertThat(result).contains("3000 字符");
    }

    @Test
    void render_justOverBoundary_truncated() {
        ToolResultRenderer renderer = createRenderer();
        // Just over threshold (3001), should be truncated
        String content = "x".repeat(3001);
        ToolOutcome outcome = ToolOutcome.success(content);
        SessionState state = SessionState.create("s1", "test");

        String result = renderer.render("read_file", outcome, state);

        assertThat(result).contains("截断提示");
        assertThat(result).contains("artifact://");
        assertThat(result).contains("3001 字符");
    }

    @Test
    void render_artifactContentReadableFromStore() {
        ArtifactStore store = new ArtifactStore(tempDir);
        ToolResultRenderer renderer = new ToolResultRenderer(store);
        String largeContent = "UNIQUE_CONTENT_" + "x".repeat(4000);
        ToolOutcome outcome = ToolOutcome.success(largeContent);
        SessionState state = SessionState.create("s1", "test");

        String result = renderer.render("read_file", outcome, state);

        // Extract refId from result
        String refIdMarker = "artifact://";
        int idx = result.indexOf(refIdMarker);
        assertThat(idx).isGreaterThan(0);
        String refId = result.substring(idx + refIdMarker.length()).split("[^a-zA-Z0-9_]")[0];
        assertThat(refId).startsWith("art_");

        // Read back the full content
        String fullContent = store.readContent(refId);
        assertThat(fullContent).isEqualTo(largeContent);
    }
}
