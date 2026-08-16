package cn.kong.eon.tool;

import cn.kong.eon.model.ArtifactRef;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具结果渲染器。
 * 对应技术方案第 5.5 节。
 * 工具结果绝不以原始 JSON 形式回填，统一走八字段语义标注。
 *
 * <p>工具本身不做任何截断/落盘操作，所有内容裁剪统一由本类处理。
 * 采用单阈值设计——截断即落盘，保证被截断的内容不会丢失：
 * <ul>
 *   <li>≤ {@value #ARTIFACT_THRESHOLD} 字符：完整内容直接写入消息，LLM 可直接读取</li>
 *   <li>> {@value #ARTIFACT_THRESHOLD} 字符：完整原文落盘为 artifact，
 *       消息中只保留摘要（前 {@value #SUMMARY_PREFIX} + 后 {@value #SUMMARY_SUFFIX} 字符）
 *       + artifact 引用，LLM 可通过引用检索完整内容</li>
 * </ul>
 * </p>
 */
public class ToolResultRenderer {
    private static final Logger log = LoggerFactory.getLogger(ToolResultRenderer.class);

    /** 落盘阈值：超过此值的结果完整原文落盘为 artifact，消息只留摘要 + 引用 */
    private static final int ARTIFACT_THRESHOLD = 3000;

    /** 摘要保留前缀长度 */
    private static final int SUMMARY_PREFIX = 700;

    /** 摘要保留后缀长度 */
    private static final int SUMMARY_SUFFIX = 300;

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
     * @param rawResult   原始结果（工具返回的完整内容，不做任何截断）
     * @param state       会话状态
     * @return            渲染后的语义标注文本
     */
    public String render(String toolName, String toolCallId, String reason, String rawResult, SessionState state) {
        if (rawResult == null) rawResult = "";
        boolean success = !rawResult.startsWith("[ERROR]");

        // 超过阈值：完整原文落盘为 artifact，消息只留摘要 + 引用
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
        sb.append("结果摘要: ").append(displayContent).append("\n");
        if (refId != null) {
            sb.append("完整内容引用: artifact://").append(refId).append("\n");
            sb.append("原始大小: ").append(rawResult.length()).append(" 字符\n");
        }
        sb.append("调用ID: ").append(toolCallId).append("\n");
        sb.append("执行轮次: ").append(state.getTurnCount()).append("\n");

        return sb.toString();
    }

    /**
     * 提取摘要：保留前 {@value #SUMMARY_PREFIX} 字符 + 尾部 {@value #SUMMARY_SUFFIX} 字符，
     * 中间用省略标记连接。
     *
     * @param content 原始内容
     * @return        截断后的摘要文本
     */
    private String extractSummary(String content) {
        if (content.length() <= SUMMARY_PREFIX + SUMMARY_SUFFIX) return content;
        return content.substring(0, SUMMARY_PREFIX)
                + "\n... [中间内容省略，完整内容已落盘，可通过 artifact:// 引用检索] ...\n"
                + content.substring(content.length() - SUMMARY_SUFFIX);
    }
}
