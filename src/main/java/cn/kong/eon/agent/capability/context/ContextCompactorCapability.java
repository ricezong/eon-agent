package cn.kong.eon.agent.capability.context;

import cn.kong.eon.agent.capability.CapabilityModule;
import cn.kong.eon.agent.capability.CapabilityResult;
import cn.kong.eon.agent.capability.Layer;
import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.context.CompressionEngine;
import cn.kong.eon.context.PairingRepairer;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.model.CompressionState;
import cn.kong.eon.model.SessionState;
import dev.langchain4j.data.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩能力模块。
 *
 * <p>属于 {@link Layer#CONTEXT} 上下文层，orderInLayer=100（默认）。
 * 始终激活。在 {@link #beforeModelCall} 中检测水位：</p>
 * <ul>
 *   <li>水位 ≥ snipThreshold：Snip（截短旧 tool result）</li>
 *   <li>水位 ≥ pruneThreshold：Prune（替换为占位符）</li>
 *   <li>水位 ≥ summarizeThreshold：Summarize（LLM 生成摘要，删除旧消息）</li>
 * </ul>
 *
 * <p>压缩后执行配对修复。
 * Summarize 会删除旧消息替换为摘要，可能导致 tool_use/tool_result 配对断裂，
 * 因此在 summarizeThreshold 触发时调用 PairingRepairer.repair()。
 * Snip/Prune 只截短 content 不删除消息，不破坏配对。</p>
 *
 * <h3>重构说明</h3>
 * <ul>
 *   <li>priority() → layer()=CONTEXT。</li>
 *   <li>beforeModelCall 返回值 void → CapabilityResult（始终返回 ok，不中断）。</li>
 * </ul>
 */
public class ContextCompactorCapability implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(ContextCompactorCapability.class);

    private final AgentConfig config;
    private final CompressionEngine compressionEngine;
    private final PairingRepairer pairingRepairer;

    public ContextCompactorCapability(AgentConfig config, LlmClient llmClient) {
        this.config = config;
        this.compressionEngine = new CompressionEngine(
                config.getContext().snipThreshold,
                config.getContext().pruneThreshold,
                config.getContext().summarizeThreshold,
                config.getContext().snipKeepChars,
                config.getContext().pruneKeepChars,
                config.getContext().summarizeMaxInputChars,
                config.getContext().summarizeMaxOutputChars,
                llmClient);
        this.pairingRepairer = new PairingRepairer();
    }

    @Override
    public String name() { return "ContextCompactor"; }

    @Override
    public boolean isActive(SessionState state) { return true; }

    @Override
    public Layer layer() { return Layer.CONTEXT; }

    @Override
    public CapabilityResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        // 估算水位
        long usedTokens = ctx.estimateTokens();
        long maxTokens = config.getContext().maxTokens;
        double waterLevel = Math.min(1.0, (double) usedTokens / maxTokens);
        state.getCompressionState().setLastWaterLevel(waterLevel);

        log.debug("Water level: {}% ({}/{})", String.format("%.1f", waterLevel * 100), usedTokens, maxTokens);

        if (waterLevel < config.getContext().snipThreshold) {
            return CapabilityResult.ok();  // 低水位，不压缩
        }

        // 高水位：压缩 transcript
        List<ChatMessage> transcript = ctx.getTranscript();
        if (transcript == null || transcript.isEmpty()) return CapabilityResult.ok();

        CompressionState cs = state.getCompressionState();
        int tailGuardTurns = config.getContext().tailGuardMinTurns;

        // 压缩（CompressionEngine 内部会按水位执行 Snip/Prune/Summarize 三级递进）
        List<ChatMessage> compressed = compressionEngine.compress(
                new ArrayList<>(transcript), cs, waterLevel, tailGuardTurns);

        // 配对修复：仅在触发 Summarize（水位 ≥ summarizeThreshold）时执行。
        // Snip/Prune 只截短 content 不删除消息，不破坏 tool_use/tool_result 配对。
        // 只有 Summarize 会删除旧消息替换为摘要，可能导致配对断裂，才需要修复。
        if (waterLevel >= config.getContext().summarizeThreshold) {
            compressed = pairingRepairer.repair(compressed);
            log.debug("Pairing repair applied (summarize threshold reached)");
        }

        ctx.setTranscript(compressed);

        log.info("Context compressed: water={}% ({} -> {} messages)",
                String.format("%.1f", waterLevel * 100), transcript.size(), compressed.size());

        return CapabilityResult.ok();
    }
}
