package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.ContentTrimmer;
import cn.kong.eon.agent.context.StoreSupport;
import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.BlockProjector;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.model.ArtifactRef;
import dev.langchain4j.data.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * 入站管线。所有内容进入上下文的唯一关卡。
 * 消息爆炸为块后，对工具结果统一格式化，超阈值的执行落盘。
 */
public class ContextPipeline {

    private static final Logger log = LoggerFactory.getLogger(ContextPipeline.class);

    private final ContentTrimmer compressor;
    private final StoreSupport storeSupport;
    private final int spillThresholdChars;
    private final int spillKeepChars;

    public ContextPipeline(ContentTrimmer compressor,
                           StoreSupport storeSupport,
                           int spillThresholdChars,
                           int spillKeepChars) {
        this.compressor = compressor;
        this.storeSupport = storeSupport;
        this.spillThresholdChars = spillThresholdChars;
        this.spillKeepChars = spillKeepChars;
    }

    /**
     * 消息入站：爆炸为块 → 工具结果格式化 → 大结果落盘 → 返回进入上下文的块。
     */
    public List<ContextBlock> ingest(ChatMessage msg, Set<String> succeededToolCalls, int messageSeq) {
        List<ContextBlock> blocks = BlockProjector.explode(msg, messageSeq);
        for (ContextBlock block : blocks) {
            if (block.kind() == BlockKind.TOOL_RESULT) {
                boolean succeeded = succeededToolCalls != null
                        && block.toolCallId() != null
                        && succeededToolCalls.contains(block.toolCallId());
                if (block.chars() > spillThresholdChars) {
                    spillArtifact(block, succeeded);
                } else {
                    formatResult(block, succeeded);
                }
            }
        }
        return blocks;
    }

    // ═══════════════════ 工具结果格式化 ═══════════════════

    /**
     * 普通工具结果：套展示外壳，附带工具名与执行状态。
     */
    private static void formatResult(ContextBlock block, boolean succeeded) {
        String toolName = block.toolName() != null ? block.toolName() : "unknown";
        block.setText("""
                [Tool result] %s
                ├─ 状态: %s
                └─ 内容:
                %s""".formatted(toolName, succeeded ? "成功" : "失败", block.text()));
    }

    /**
     * 大工具结果落盘：完整内容写入 artifact，块里只留头尾摘要 + 截断提示 + artifact 引用。
     */
    private void spillArtifact(ContextBlock block, boolean succeeded) {
        String raw = block.text();
        String summary = compressor.headTail(raw, spillKeepChars);

        ArtifactRef ref = storeSupport.save(block.toolName(), raw, block.messageSeq());
        if (ref == null) {
            log.warn("[入站] artifact 落盘不可用，{} 保留原文入窗", block.toolName());
            formatResult(block, succeeded);
            return;
        }

        String toolName = block.toolName() != null ? block.toolName() : "unknown";
        block.setSpilled(true);
        block.setText("""
                [Tool result] %s
                ├─ 状态: %s
                ├─ 内容:
                %s
                ├─ 截断提示: 内容过大（%d 字符），已截断为 %d 字符摘要
                └─ 元数据: 完整内容已保存至 artifact://%s，可用 read_file 工具读取该引用获取完整内容""".formatted(
                toolName, succeeded ? "成功" : "失败", summary, raw.length(), summary.length(), ref.refId()));

        log.info("[入站] {} 落盘: {} ({} -> {} 字符)", block.toolName(), ref.refId(), raw.length(), raw.length() - summary.length());
    }
}
