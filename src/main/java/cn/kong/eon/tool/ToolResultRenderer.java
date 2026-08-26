package cn.kong.eon.tool;

import cn.kong.eon.model.ArtifactRef;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具结果渲染器。统一格式化工具输出，控制大小，注入元数据。
 * 大结果处理：超过阈值时原文落盘为 artifact，消息只留头部 + 尾部摘要 + 引用。
 */
public class ToolResultRenderer {
    private static final Logger log = LoggerFactory.getLogger(ToolResultRenderer.class);

    private static final int ARTIFACT_THRESHOLD = 3000;
    private static final int SUMMARY_HEAD = 700;
    private static final int SUMMARY_TAIL = 300;

    private final ArtifactStore artifactStore;

    public ToolResultRenderer(ArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    /**
     * 渲染工具结果为统一格式化输出。
     * 小结果直接写入；大结果落盘为 artifact，消息只保留头尾摘要 + 引用。
     */
    public String render(String toolName, ToolOutcome outcome, SessionState state) {
        String rawResult = outcome.content();
        boolean success = outcome.success();

        String refId = null;
        String displayContent = rawResult;
        boolean truncated = false;

        if (rawResult.length() > ARTIFACT_THRESHOLD) {
            String summary = extractHeadTailSummary(rawResult);
            ArtifactRef ref = artifactStore.save(toolName, rawResult, summary);
            refId = ref.getRefId();
            displayContent = summary;
            truncated = true;
            log.info("[渲染] {} -> artifact {} ({} -> {} 字符)",
                    toolName, refId, rawResult.length(), summary.length());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[Tool result] ").append(toolName).append("\n");
        sb.append("├─ 状态: ").append(success ? "成功" : "失败").append("\n");
        sb.append("├─ 内容:\n").append(displayContent);

        if (truncated && refId != null) {
            sb.append("\n├─ 截断提示: 内容过大（").append(rawResult.length())
                    .append(" 字符），已截断为头部 ").append(SUMMARY_HEAD)
                    .append(" + 尾部 ").append(SUMMARY_TAIL).append(" 字符摘要");
            sb.append("\n└─ 元数据: 完整内容已保存至 artifact://").append(refId)
                    .append("，可用 read_file 工具读取该引用获取完整内容");
        } else {
            sb.append("\n└─ 元数据: ").append(rawResult.length()).append(" 字符");
        }
        return sb.toString();
    }

    /**
     * 头尾保留摘要：保留前 SUMMARY_HEAD 字符和后 SUMMARY_TAIL 字符，中间用省略号替代。
     */
    private String extractHeadTailSummary(String content) {
        if (content.length() <= SUMMARY_HEAD + SUMMARY_TAIL) return content;
        String head = content.substring(0, SUMMARY_HEAD);
        String tail = content.substring(content.length() - SUMMARY_TAIL);
        return head
                + "\n... [中间内容已省略，完整内容已落盘为 artifact] ...\n"
                + tail;
    }
}
