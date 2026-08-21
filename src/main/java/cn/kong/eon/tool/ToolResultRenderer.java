package cn.kong.eon.tool;

import cn.kong.eon.model.ArtifactRef;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具结果渲染器。
 * ≤3000 字符：完整内容直接写入消息。
 * >3000 字符：原文落盘为 artifact，消息只留摘要 + 引用。
 */
public class ToolResultRenderer {
    private static final Logger log = LoggerFactory.getLogger(ToolResultRenderer.class);

    private static final int ARTIFACT_THRESHOLD = 3000;
    private static final int SUMMARY_PREFIX = 700;
    private static final int SUMMARY_SUFFIX = 300;

    private final ArtifactStore artifactStore;

    public ToolResultRenderer(ArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    /** 渲染工具结果为简洁语义标注。 */
    public String render(String toolName, ToolOutcome outcome, SessionState state) {
        String rawResult = outcome.content();
        boolean success = outcome.success();

        String refId = null;
        String displayContent = rawResult;

        if (rawResult.length() > ARTIFACT_THRESHOLD) {
            String summary = extractSummary(rawResult);
            ArtifactRef ref = artifactStore.save(toolName, rawResult, summary);
            refId = ref.getRefId();
            displayContent = summary;
            log.info("[Render] {} -> artifact {} ({} -> {} chars)",
                    toolName, refId, rawResult.length(), summary.length());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[工具: ").append(toolName).append(" | ").append(success ? "成功" : "失败").append("]\n");
        sb.append(displayContent);
        if (refId != null) {
            sb.append("\n[完整内容: artifact://").append(refId).append("]");
        }
        return sb.toString();
    }

    /** 提取摘要：保留前缀 + 后缀，中间省略。 */
    private String extractSummary(String content) {
        if (content.length() <= SUMMARY_PREFIX + SUMMARY_SUFFIX) return content;
        return content.substring(0, SUMMARY_PREFIX)
                + "\n... [中间内容省略，完整内容已落盘] ...\n"
                + content.substring(content.length() - SUMMARY_SUFFIX);
    }
}
