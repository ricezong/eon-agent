package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.store.ArtifactStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入站管线测试。
 * <p>
 * 承接原 {@code ToolResultRendererTest} 的全部断言：落盘与格式化逻辑
 * 从工具执行层上移到这里之后，对外行为必须逐条保持一致。
 * <p>
 * 阈值沿用原设计的层层递进：
 * <pre>
 *   落盘阈值 = snipKeepChars × 3 = 6000
 *   落盘摘要 = snipKeepChars × 2 = 4000（&gt; snipKeepChars，可被 Snip 二次截断）
 * </pre>
 */
class IngestPipelineTest {

    private static final int SNIP_KEEP_CHARS = 2000;
    private static final int ARTIFACT_THRESHOLD = SNIP_KEEP_CHARS * 3;
    private static final int SUMMARY_KEEP_CHARS = SNIP_KEEP_CHARS * 2;

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    private ContextPipeline pipeline(ArtifactStore store) {
        return new ContextPipeline(
                List.of(new ArtifactSpillRule(), new ToolResultFormatRule()),
                store, null, mapper, SNIP_KEEP_CHARS, SNIP_KEEP_CHARS);
    }

    private List<ContextBlock> ingest(ContextPipeline p, String id, String toolName,
                                      String content, boolean success) {
        ToolExecutionResultMessage msg = ToolExecutionResultMessage.from(id, toolName, content);
        return p.ingest(msg, 1, success ? Set.of(id) : Set.of());
    }

    private String render(ArtifactStore store, String toolName, String content, boolean success) {
        return ingest(pipeline(store), "c1", toolName, content, success).get(0).text();
    }

    // ═══════════════════ 小结果：不落盘 ═══════════════════

    @Test
    void smallResult_directOutputWithoutArtifact() {
        String result = render(new ArtifactStore(tempDir), "read_file", "hello world", true);

        assertThat(result).contains("[Tool result] read_file");
        assertThat(result).contains("成功");
        assertThat(result).contains("hello world");
        assertThat(result).contains("11 字符");
        assertThat(result).doesNotContain("artifact://");
        assertThat(result).doesNotContain("截断提示");
    }

    // ═══════════════════ 大结果：落盘 + 头尾摘要 ═══════════════════

    @Test
    void largeResult_truncatedAndSavedAsArtifact() {
        String largeContent = "x".repeat(ARTIFACT_THRESHOLD + 100);
        String result = render(new ArtifactStore(tempDir), "read_file", largeContent, true);

        assertThat(result).contains("[Tool result] read_file");
        assertThat(result).contains("成功");
        assertThat(result).contains("截断提示");
        assertThat(result).contains(ARTIFACT_THRESHOLD + 100 + " 字符");
        assertThat(result).contains("artifact://");
        assertThat(result.length()).isLessThan(largeContent.length());
    }

    @Test
    void largeResult_keepsHeadAndTail() {
        int headChars = SUMMARY_KEEP_CHARS / 2;
        int tailChars = SUMMARY_KEEP_CHARS - headChars;
        String largeContent = "HEAD_MARKER_" + "h".repeat(headChars)
                + "M".repeat(2000)
                + "t".repeat(tailChars) + "TAIL_MARKER_";

        String result = render(new ArtifactStore(tempDir), "read_file", largeContent, true);

        assertThat(result).contains("HEAD_MARKER_");
        assertThat(result).contains("TAIL_MARKER_");
        assertThat(result).contains("中间内容已省略");
        assertThat(result).doesNotContain("MMMMMM");
    }

    @Test
    void largeResult_artifactContentReadableFromStore() {
        ArtifactStore store = new ArtifactStore(tempDir);
        String largeContent = "UNIQUE_CONTENT_" + "x".repeat(ARTIFACT_THRESHOLD + 100);

        List<ContextBlock> blocks = ingest(pipeline(store), "c1", "read_file", largeContent, true);
        ContextBlock block = blocks.get(0);

        assertThat(block.refId()).isNotNull().startsWith("art_");
        assertThat(store.readContent(block.refId())).isEqualTo(largeContent);
    }

    // ═══════════════════ 边界 ═══════════════════

    @Test
    void boundarySize_notTruncated() {
        String content = "x".repeat(ARTIFACT_THRESHOLD);
        String result = render(new ArtifactStore(tempDir), "read_file", content, true);

        assertThat(result).doesNotContain("截断提示");
        assertThat(result).doesNotContain("artifact://");
        assertThat(result).contains(ARTIFACT_THRESHOLD + " 字符");
    }

    @Test
    void justOverBoundary_truncated() {
        String content = "x".repeat(ARTIFACT_THRESHOLD + 1);
        String result = render(new ArtifactStore(tempDir), "read_file", content, true);

        assertThat(result).contains("截断提示");
        assertThat(result).contains("artifact://");
        assertThat(result).contains(ARTIFACT_THRESHOLD + 1 + " 字符");
    }

    // ═══════════════════ 失败结果 ═══════════════════

    @Test
    void failureResult_showsFailureStatus() {
        String result = render(new ArtifactStore(tempDir), "read_file", "file not found", false);

        assertThat(result).contains("[Tool result] read_file");
        assertThat(result).contains("失败");
        assertThat(result).contains("file not found");
        assertThat(result).doesNotContain("artifact://");
    }

    @Test
    void largeFailureResult_alsoTruncated() {
        String largeError = "E".repeat(ARTIFACT_THRESHOLD + 100);
        String result = render(new ArtifactStore(tempDir), "write_file", largeError, false);

        assertThat(result).contains("截断提示");
        assertThat(result).contains("artifact://");
    }

    @Test
    void resultBlock_recordsSuccess_onBlock() {
        List<ContextBlock> ok = ingest(pipeline(new ArtifactStore(tempDir)), "c1", "read_file", "x", true);
        List<ContextBlock> failed = ingest(pipeline(new ArtifactStore(tempDir)), "c2", "read_file", "x", false);

        assertThat(ok.get(0).success()).isTrue();
        assertThat(failed.get(0).success()).isFalse();
    }

    // ═══════════════════ 管道衔接 ═══════════════════

    /**
     * 落盘摘要（4000）必须大于 Snip 阈值（2000），
     * 否则 Snip 对已落盘的块无事可做，两级处置之间会出现空档。
     */
    @Test
    void summaryExceedsSnipThreshold_canBeSnippedAgain() {
        String largeContent = "H".repeat(SUMMARY_KEEP_CHARS) + "M".repeat(2000) + "T".repeat(SUMMARY_KEEP_CHARS);
        String result = render(new ArtifactStore(tempDir), "read_file", largeContent, true);

        assertThat(result).contains("artifact://");
        assertThat(result).contains("截断提示");

        int contentStart = result.indexOf("├─ 内容:\n") + "├─ 内容:\n".length();
        int contentEnd = result.indexOf("\n├─ 截断提示");
        String displayContent = result.substring(contentStart, contentEnd);
        assertThat(displayContent.length()).isGreaterThan(SNIP_KEEP_CHARS);
    }

    /**
     * 规则按声明顺序执行：落盘必须先于格式化——
     * 格式化后内容里混了元信息，就不能再当作原文落盘了。
     */
    @Test
    void rules_executeInDeclarationOrder() {
        ArtifactStore store = new ArtifactStore(tempDir);
        ContextPipeline p = new ContextPipeline(
                List.of(new ArtifactSpillRule(), new ToolResultFormatRule()),
                store, null, mapper, SNIP_KEEP_CHARS, SNIP_KEEP_CHARS);

        String largeContent = "x".repeat(ARTIFACT_THRESHOLD + 100);
        ContextBlock block = ingest(p, "c1", "read_file", largeContent, true).get(0);

        // 若顺序颠倒，落盘会把带外壳的文本当原文存进去，且不会产生 artifact://
        assertThat(block.refId()).isNotNull();
        assertThat(block.text()).contains("artifact://");
        assertThat(store.readContent(block.refId())).isEqualTo(largeContent);
    }

    @Test
    void nonToolResult_untouchedByPipeline() {
        dev.langchain4j.data.message.UserMessage user =
                dev.langchain4j.data.message.UserMessage.from("keep me verbatim");
        List<ContextBlock> blocks = pipeline(new ArtifactStore(tempDir)).ingest(user, 1, Set.of());

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).text()).isEqualTo("keep me verbatim");
    }
}
