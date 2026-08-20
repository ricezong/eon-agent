package cn.kong.eon.agent.hook.premodel;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
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
 * 上下文压缩（模型调用前阶段，order=100）。
 * 检测水位：≥snip 截短 → ≥prune 占位符 → ≥summarize LLM 摘要并删旧消息。
 * Summarize 后执行 PairingRepairer 修复配对断裂。
 */
public class ContextCompactHook implements Hook.PreModelHook {
    private static final Logger log = LoggerFactory.getLogger(ContextCompactHook.class);

    private final AgentConfig config;
    private final CompressionEngine compressionEngine;
    private final PairingRepairer pairingRepairer;

    public ContextCompactHook(AgentConfig config, LlmClient llmClient) {
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

    @Override public String name() { return "ContextCompact"; }
    @Override public boolean isActive(SessionState state) { return true; }

    @Override
    public HookResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        long usedTokens = ctx.estimateTokens();
        long maxTokens = config.getContext().maxTokens;
        double waterLevel = Math.min(1.0, (double) usedTokens / maxTokens);
        state.getCompressionState().setLastWaterLevel(waterLevel);

        if (waterLevel < config.getContext().snipThreshold) {
            return HookResult.ok();
        }

        List<ChatMessage> transcript = ctx.getTranscript();
        if (transcript == null || transcript.isEmpty()) return HookResult.ok();

        CompressionState cs = state.getCompressionState();
        int tailGuardTurns = config.getContext().tailGuardMinTurns;

        List<ChatMessage> compressed = compressionEngine.compress(
                new ArrayList<>(transcript), cs, waterLevel, tailGuardTurns);

        if (waterLevel >= config.getContext().summarizeThreshold) {
            compressed = pairingRepairer.repair(compressed);
        }

        ctx.setTranscript(compressed);
        long postCompressTokens = ctx.estimateTokens();
        log.info("[PreModel] ContextCompact: water {}% | {} -> {} msgs | est. {} -> {} tokens",
                String.format("%.0f", waterLevel * 100), transcript.size(), compressed.size(),
                usedTokens, postCompressTokens);

        return HookResult.ok();
    }
}
