package cn.kong.eon.agent.capability;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.context.CompressionEngine;
import cn.kong.eon.context.PairingRepairer;
import cn.kong.eon.model.CompressionState;
import cn.kong.eon.model.SessionState;
import dev.langchain4j.data.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 上下文压缩能力模块。
 *
 * 始终激活。在 beforeModelCall 中检测水位：
 * - 水位 ≥ snipThreshold：Snip（截短旧 tool result）
 * - 水位 ≥ pruneThreshold：Prune（替换为占位符）
 * - 水位 ≥ summarizeThreshold：Summarize（LLM 摘要，MVP 暂未实现）
 *
 * 压缩后执行配对修复。
 */
public class ContextCompactor implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);

    private final AgentConfig config;
    private final CompressionEngine compressionEngine;
    private final PairingRepairer pairingRepairer;

    public ContextCompactor(AgentConfig config) {
        this.config = config;
        this.compressionEngine = new CompressionEngine(
                config.getContext().snipThreshold,
                config.getContext().pruneThreshold,
                config.getContext().snipKeepChars,
                config.getContext().pruneKeepChars);
        this.pairingRepairer = new PairingRepairer();
    }

    @Override
    public String name() { return "ContextCompactor"; }

    @Override
    public boolean isActive(SessionState state) { return true; }

    @Override
    public void beforeModelCall(SessionState state, ContextBuilder ctx) {
        // 估算水位
        long usedTokens = ctx.estimateTokens();
        long maxTokens = config.getContext().maxTokens;
        double waterLevel = Math.min(1.0, (double) usedTokens / maxTokens);
        state.getCompressionState().setLastWaterLevel(waterLevel);

        log.debug("Water level: {:.1f}% ({}/{})", waterLevel * 100, usedTokens, maxTokens);

        if (waterLevel < config.getContext().snipThreshold) {
            return;  // 低水位，不压缩
        }

        // 高水位：压缩 transcript
        List<ChatMessage> transcript = ctx.getTranscript();
        if (transcript == null || transcript.isEmpty()) return;

        CompressionState cs = state.getCompressionState();
        int tailGuardTurns = config.getContext().tailGuardMinTurns;

        // 压缩
        List<ChatMessage> compressed = compressionEngine.compress(
                new java.util.ArrayList<>(transcript), cs, waterLevel, tailGuardTurns);

        // 配对修复：仅在触发 Summarize（水位 ≥ summarizeThreshold）时执行。
        // Snip/Prune 只截短 content 不删除消息，不破坏 tool_use/tool_result 配对。
        // 只有 Summarize 会删除旧消息替换为摘要，可能导致配对断裂，才需要修复。
        if (waterLevel >= config.getContext().summarizeThreshold) {
            compressed = pairingRepairer.repair(compressed);
            log.debug("Pairing repair applied (summarize threshold reached)");
        }

        ctx.setTranscript(compressed);

        log.info("Context compressed: water={:.1f}%, {} -> {} messages",
                waterLevel * 100, transcript.size(), compressed.size());
    }
}
