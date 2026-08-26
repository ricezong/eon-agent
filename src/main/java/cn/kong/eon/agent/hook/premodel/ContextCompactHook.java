package cn.kong.eon.agent.hook.premodel;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.agent.context.CompressionEngine;
import cn.kong.eon.agent.context.PairingRepairer;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.model.CompressionState;
import cn.kong.eon.model.SessionState;
import dev.langchain4j.data.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩（PreModel, order=100）。
 * 三级递进：≥snip 截短工具结果 → ≥prune 替换为占位符 → ≥summarize LLM 摘要并删旧消息。
 * Summarize 后执行 PairingRepairer 修复配对断裂。
 */
public class ContextCompactHook implements Hook.PreModelHook {
    private static final Logger log = LoggerFactory.getLogger(ContextCompactHook.class);

    @Override public String name() { return "ContextCompact"; }
    @Override public boolean isActive(SessionState state) { return true; }
    @Override public int order() { return 100; }

    private final AgentConfig config;
    private final CompressionEngine compressionEngine;
    private final PairingRepairer pairingRepairer;
    private final String transcriptPath;

    public ContextCompactHook(AgentConfig config, LlmClient llmClient, String transcriptPath) {
        this.config = config;
        this.transcriptPath = transcriptPath;
        var ctxCfg = config.getContext();
        this.compressionEngine = new CompressionEngine(
                ctxCfg.getCompression().getSnipThreshold(),
                ctxCfg.getCompression().getPruneThreshold(),
                ctxCfg.getCompression().getSummarizeThreshold(),
                ctxCfg.getSnipKeepChars(),
                ctxCfg.getSummarizeMaxInputChars(),
                ctxCfg.getSummarizeMaxOutputChars(),
                llmClient,
                transcriptPath);
        this.pairingRepairer = new PairingRepairer();
    }

    @Override
    public HookResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        long usedTokens = ctx.estimateTokens();
        long maxTokens = config.getContext().getMaxTokens();
        double waterLevel = Math.min(1.0, (double) usedTokens / maxTokens);
        state.getCompressionState().setLastWaterLevel(waterLevel);

        // 轮数触发：距上次轮数压缩 >= summarize_turns 且有可裁剪内容
        List<ChatMessage> transcript = ctx.getTranscript();
        int turnsSinceLastCompress = state.getTurnCount() - state.getCompressionState().getLastTurnCompressed();
        boolean turnTriggered = turnsSinceLastCompress >= config.getSummarizeTurns()
                && transcript != null && transcript.size() > config.getContext().getTailGuardMinTurns() * 2 + 2;

        boolean waterTriggered = waterLevel >= config.getContext().getCompression().getSnipThreshold();

        if (!waterTriggered && !turnTriggered) {
            return HookResult.ok();
        }

        if (transcript == null || transcript.isEmpty()) return HookResult.ok();

        CompressionState cs = state.getCompressionState();
        int tailGuardTurns = config.getContext().getTailGuardMinTurns();

        List<ChatMessage> workingCopy = new ArrayList<>(transcript);

        if (waterTriggered) {
            // 水位触发的正常压缩流程：Snip → Prune → Summarize（递进）
            compressionEngine.compress(workingCopy, cs, waterLevel, tailGuardTurns);
        } else {
            // 仅轮数触发：只做 Snip（+ Prune 如水位达标），不做 Summarize
            compressionEngine.compressByTurnCount(workingCopy, cs, waterLevel, tailGuardTurns);
        }

        // Summarize 执行后需要修复配对（仅水位触发路径可能 Summarize）
        boolean didSummarize = cs.getSummarizedUpToIndex() >= 0
                && cs.getSummarizedUpToIndex() < workingCopy.size()
                && waterTriggered;
        if (didSummarize) {
            workingCopy = pairingRepairer.repair(workingCopy);
        }

        ctx.setTranscript(workingCopy);
        // 记录本次轮数压缩的 turnCount
        if (turnTriggered) {
            state.getCompressionState().setLastTurnCompressed(state.getTurnCount());
        }
        long postCompressTokens = ctx.estimateTokens();
        String trigger = waterTriggered ? "water " + String.format("%.0f%%", waterLevel * 100) : "turn " + state.getTurnCount();
        log.info("[PreModel] ContextCompact: {} | {} -> {} msgs | est. {} -> {} tokens",
                trigger, transcript.size(), workingCopy.size(), usedTokens, postCompressTokens);

        return HookResult.ok();
    }
}
