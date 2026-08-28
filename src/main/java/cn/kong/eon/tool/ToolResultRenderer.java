package cn.kong.eon.tool;

import cn.kong.eon.model.ArtifactRef;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具结果渲染器。统一格式化工具输出，控制大小，注入元数据。
 * <p>
 * 大结果处理：超过落盘阈值时原文落盘为 artifact，消息只留头尾摘要 + 引用。
 * <p>
 * 四个大小层层递进，保证压缩管道可逐级缩减：
 * <ul>
 *   <li>落盘阈值 = snipKeepChars × 3：原文超过此值才落盘</li>
 *   <li>落盘摘要 = snipKeepChars × 2：落盘后消息中的摘要大小（头尾各半）</li>
 *   <li>Snip 判断 = snipKeepChars：摘要 > snipKeepChars，可被 Snip 截断</li>
 *   <li>Snip 截断后 = snipKeepChars：头尾各半，Snip 后大小</li>
 *   <li>Prune 替换为占位符：仅保留 artifact 引用</li>
 * </ul>
 * 管道示例（snipKeepChars=4000）：
 * <pre>
 * 原文 50000 字符
 *   → 落盘: 摘要 8000 字符 (头4000+尾4000) + artifact://art_001
 *   → Snip: 8000 > 4000 → 截断为 4000 字符 (头2000+尾2000) + artifact 引用保留
 *   → Prune: 替换为 ~60 字符占位符 [旧工具结果内容已清除。引用: art_001]
 * </pre>
 */
public class ToolResultRenderer {
    private static final Logger log = LoggerFactory.getLogger(ToolResultRenderer.class);

    private final ArtifactStore artifactStore;
    private final int artifactThreshold;   // 落盘阈值：原文超过此值才落盘
    private final int summaryKeepChars;      // 落盘摘要保留字符数（头尾各半）

    /**
     * 从 snipKeepChars 派生落盘阈值和摘要大小，保证压缩管道层层递进。
     *
     * @param artifactStore artifact 存储
     * @param snipKeepChars Snip 压缩保留字符数（来自 context.snip_keep_chars 配置）
     */
    public ToolResultRenderer(ArtifactStore artifactStore, int snipKeepChars) {
        this.artifactStore = artifactStore;
        this.summaryKeepChars = snipKeepChars * 2;    // 摘要 = snipKeepChars × 2，保证 > snipKeepChars 可被 Snip 截断
        this.artifactThreshold = snipKeepChars * 3;   // 落盘阈值 = snipKeepChars × 3，保证 > 摘要大小
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

        if (rawResult.length() > artifactThreshold) {
            String summary = extractHeadTailSummary(rawResult);
            ArtifactRef ref = artifactStore.save(toolName, rawResult, summary);
            refId = ref.getRefId();
            displayContent = summary;
            truncated = true;
            log.info("[渲染] {} -> artifact {} ({} -> {} 字符)",
                    toolName, refId, rawResult.length(), summary.length());
        }

        int headChars = summaryKeepChars / 2;
        int tailChars = summaryKeepChars - headChars;

        StringBuilder sb = new StringBuilder();
        sb.append("[Tool result] ").append(toolName).append("\n");
        sb.append("├─ 状态: ").append(success ? "成功" : "失败").append("\n");
        sb.append("├─ 内容:\n").append(displayContent);

        if (truncated && refId != null) {
            sb.append("\n├─ 截断提示: 内容过大（").append(rawResult.length())
                    .append(" 字符），已截断为头部 ").append(headChars)
                    .append(" + 尾部 ").append(tailChars).append(" 字符摘要");
            sb.append("\n└─ 元数据: 完整内容已保存至 artifact://").append(refId)
                    .append("，可用 read_file 工具读取该引用获取完整内容");
        } else {
            sb.append("\n└─ 元数据: ").append(rawResult.length()).append(" 字符");
        }
        return sb.toString();
    }

    /**
     * 头尾保留摘要：保留前 headChars 字符和后 tailChars 字符，中间用省略号替代。
     */
    private String extractHeadTailSummary(String content) {
        int headChars = summaryKeepChars / 2;
        int tailChars = summaryKeepChars - headChars;
        if (content.length() <= headChars + tailChars) return content;
        String head = content.substring(0, headChars);
        String tail = content.substring(content.length() - tailChars);
        return head
                + "\n... [中间内容已省略，完整内容已落盘为 artifact] ...\n"
                + tail;
    }
}
