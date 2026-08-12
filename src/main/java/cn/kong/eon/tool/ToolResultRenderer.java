package cn.kong.eon.tool;

import cn.kong.eon.model.ArtifactRef;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 工具结果渲染器。
 * 对应技术方案第 5.5 节。
 * 工具结果绝不以原始 JSON 形式回填，统一走八字段语义标注。
 * 大于阈值的结果落盘为 artifact，上下文只留引用。
 */
public class ToolResultRenderer {
    private static final Logger log = LoggerFactory.getLogger(ToolResultRenderer.class);

    private static final int ARTIFACT_THRESHOLD = 8000;  // 字符

    private final ArtifactStore artifactStore;

    public ToolResultRenderer(ArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    /**
     * 渲染工具结果为语义标注。
     *
     * @param toolName    工具名
     * @param toolCallId  调用 ID
     * @param reason      调用原因（模型填写）
     * @param rawResult   原始结果
     * @param state       会话状态
     * @return            渲染后的语义标注文本
     */
    public String render(String toolName, String toolCallId, String reason,
                         String rawResult, SessionState state) {
        if (rawResult == null) rawResult = "";
        boolean success = !rawResult.startsWith("[ERROR]");

        // 大文本落盘为 artifact
        String refId = null;
        String displayContent = rawResult;
        if (rawResult.length() > ARTIFACT_THRESHOLD) {
            String summary = extractSummary(rawResult);
            ArtifactRef ref = artifactStore.save(toolName, rawResult, summary);
            refId = ref.getRefId();
            displayContent = summary;
            log.info("Large result saved as artifact: {} ({} chars -> {})",
                    refId, rawResult.length(), summary.length());
        }

        // 构建八字段语义标注
        StringBuilder sb = new StringBuilder();
        sb.append("[工具执行结果: ").append(toolName).append("]\n");
        sb.append("执行状态: ").append(success ? "成功" : "失败").append("\n");
        sb.append("调用原因: ").append(reason != null ? reason : "(未提供)").append("\n");
        sb.append("结果摘要: ").append(truncate(displayContent, 300)).append("\n");
        if (refId != null) {
            sb.append("完整内容引用: artifact://").append(refId).append("\n");
            sb.append("原始大小: ").append(rawResult.length()).append(" 字符\n");
        }
        sb.append("调用ID: ").append(toolCallId).append("\n");
        sb.append("执行轮次: ").append(state.getTurnCount()).append("\n");

        return sb.toString();
    }

    /**
     * 提取摘要：取前 300 字符 + 尾部 100 字符。
     */
    private String extractSummary(String content) {
        if (content.length() <= 400) return content;
        return content.substring(0, 300)
                + "\n... [中间内容省略] ...\n"
                + content.substring(content.length() - 100);
    }

    /**
     * 截断文本。
     */
    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
