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

    // Test config: snipKeepChars=2000
    // → artifactThreshold = 2000 * 3 = 6000
    // → summaryKeepChars  = 2000 * 2 = 4000 (head 2000 + tail 2000)
    private static final int SNIP_KEEP_CHARS = 2000;
    private static final int ARTIFACT_THRESHOLD = SNIP_KEEP_CHARS * 3;   // 6000
    private static final int SUMMARY_KEEP_CHARS = SNIP_KEEP_CHARS * 2;  // 4000

    private ToolResultRenderer createRenderer() {
        return new ToolResultRenderer(new ArtifactStore(tempDir), SNIP_KEEP_CHARS);
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
        String largeContent = "x".repeat(ARTIFACT_THRESHOLD + 100);
        ToolOutcome outcome = ToolOutcome.success(largeContent);
        SessionState state = SessionState.create("s1", "test");

        String result = renderer.render("read_file", outcome, state);

        assertThat(result).contains("[Tool result] read_file");
        assertThat(result).contains("成功");
        assertThat(result).contains("截断提示");
        assertThat(result).contains(ARTIFACT_THRESHOLD + 100 + " 字符");
        assertThat(result).contains("artifact://");
        // The display content should be shorter than the original
        assertThat(result.length()).isLessThan(largeContent.length());
    }

    @Test
    void render_largeResult_headTailSummary() {
        ToolResultRenderer renderer = createRenderer();
        // Content must exceed ARTIFACT_THRESHOLD to trigger truncation
        int headChars = SUMMARY_KEEP_CHARS / 2;  // 2000
        int tailChars = SUMMARY_KEEP_CHARS - headChars; // 2000
        String head = "HEAD_MARKER_" + "h".repeat(headChars);
        String mid = "M".repeat(2000);
        String tail = "t".repeat(tailChars) + "TAIL_MARKER_";
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
        String largeError = "E".repeat(ARTIFACT_THRESHOLD + 100);
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
        // Exactly at threshold, should NOT be truncated (strictly greater than)
        String content = "x".repeat(ARTIFACT_THRESHOLD);
        ToolOutcome outcome = ToolOutcome.success(content);
        SessionState state = SessionState.create("s1", "test");

        String result = renderer.render("read_file", outcome, state);

        assertThat(result).doesNotContain("截断提示");
        assertThat(result).doesNotContain("artifact://");
        assertThat(result).contains(ARTIFACT_THRESHOLD + " 字符");
    }

    @Test
    void render_justOverBoundary_truncated() {
        ToolResultRenderer renderer = createRenderer();
        // Just over threshold, should be truncated
        String content = "x".repeat(ARTIFACT_THRESHOLD + 1);
        ToolOutcome outcome = ToolOutcome.success(content);
        SessionState state = SessionState.create("s1", "test");

        String result = renderer.render("read_file", outcome, state);

        assertThat(result).contains("截断提示");
        assertThat(result).contains("artifact://");
        assertThat(result).contains(ARTIFACT_THRESHOLD + 1 + " 字符");
    }

    @Test
    void render_artifactContentReadableFromStore() {
        ArtifactStore store = new ArtifactStore(tempDir);
        ToolResultRenderer renderer = new ToolResultRenderer(store, SNIP_KEEP_CHARS);
        String largeContent = "UNIQUE_CONTENT_" + "x".repeat(ARTIFACT_THRESHOLD + 100);
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

    /**
     * 管道衔接验证：落盘摘要大小 > snipKeepChars，可被 Snip 二次截断。
     * <p>
     * 落盘摘要 = snipKeepChars × 2 = 4000 字符
     * Snip 阈值 = snipKeepChars = 2000 字符
     * 4000 > 2000 → Snip 可截断
     */
    @Test
    void render_summaryExceedsSnipThreshold_canBeSnippedAgain() {
        ToolResultRenderer renderer = createRenderer();
        // 原文远超落盘阈值
        String largeContent = "H".repeat(SUMMARY_KEEP_CHARS) + "M".repeat(2000) + "T".repeat(SUMMARY_KEEP_CHARS);
        ToolOutcome outcome = ToolOutcome.success(largeContent);
        SessionState state = SessionState.create("s1", "test");

        String result = renderer.render("read_file", outcome, state);

        // 落盘摘要的核心内容（不含元信息）应约为 SUMMARY_KEEP_CHARS
        // 验证摘要 > snipKeepChars，确保 Snip 能二次截断
        assertThat(result).contains("artifact://");
        assertThat(result).contains("截断提示");
        // 去掉元信息后的内容部分应大于 snipKeepChars
        int contentStart = result.indexOf("├─ 内容:\n") + "├─ 内容:\n".length();
        int contentEnd = result.indexOf("\n├─ 截断提示");
        String displayContent = result.substring(contentStart, contentEnd);
        assertThat(displayContent.length()).isGreaterThan(SNIP_KEEP_CHARS);
    }
}
