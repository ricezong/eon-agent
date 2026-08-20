package cn.kong.eon.tool;

import cn.kong.eon.model.ArtifactRef;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具结果渲染器。统一走八字段语义标注，绝不以原始 JSON 回填。
 *
 * 采用单阈值设计——截断即落盘：
 *   ≤ 3000 字符：完整内容直接写入消息
 *   > 3000 字符：原文落盘为 artifact，消息只留摘要（前700+后300字符）+ 引用
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

    /** 渲染工具结果为语义标注。 */
    public String render(String toolName, String toolCallId, String reason, String rawResult, SessionState state) {
        if (rawResult == null) rawResult = "";
        boolean success = !rawResult.startsWith("[ERROR]");

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
        sb.append("[工具执行结果: ").append(toolName).append("]\n");
        sb.append("执行状态: ").append(success ? "成功" : "失败").append("\n");
        sb.append("调用原因: ").append(reason != null ? reason : "(未提供)").append("\n");
        sb.append("结果摘要: ").append(displayContent).append("\n");
        if (refId != null) {
            sb.append("完整内容引用: artifact://").append(refId).append("\n");
            sb.append("原始大小: ").append(rawResult.length()).append(" 字符\n");
        }
        sb.append("调用ID: ").append(toolCallId).append("\n");
        sb.append("执行轮次: ").append(state.getTurnCount()).append("\n");

        return sb.toString();
    }

    /** 提取摘要：保留前缀 + 后缀，中间省略。 */
    private String extractSummary(String content) {
        if (content.length() <= SUMMARY_PREFIX + SUMMARY_SUFFIX) return content;
        return content.substring(0, SUMMARY_PREFIX)
                + "\n... [中间内容省略，完整内容已落盘，可通过 artifact:// 引用检索] ...\n"
                + content.substring(content.length() - SUMMARY_SUFFIX);
    }
}
