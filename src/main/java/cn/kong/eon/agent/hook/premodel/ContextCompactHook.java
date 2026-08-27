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

    @Override
    public String name() {
        return "ContextCompact";
    }

    /**
     * 始终激活。上下文压缩是每次模型调用前的必要检查。
     */
    @Override
    public boolean isActive(SessionState state) {
        return true;
    }

    /**
     * 执行顺序 100（默认值）。若存在其他 PreModelHook 需要在压缩前/后执行，
     * 可通过调整 order 值控制先后顺序。
     */
    @Override
    public int order() {
        return 100;
    }

    private final AgentConfig config;
    private final CompressionEngine compressionEngine;
    private final PairingRepairer pairingRepairer;
    private final String transcriptPath;

    /**
     * 构造函数：从 AgentConfig 中读取压缩相关配置，初始化 CompressionEngine 和 PairingRepairer。
     *
     * @param config        Agent 全局配置，包含压缩阈值、尾部保护轮数等参数
     * @param llmClient     LLM 客户端，供 CompressionEngine 在 Summarize 阶段调用 LLM 生成摘要
     * @param transcriptPath 原始对话记录文件路径，嵌入摘要提示词中供后续回溯
     */
    public ContextCompactHook(AgentConfig config, LlmClient llmClient, String transcriptPath) {
        this.config = config;
        this.transcriptPath = transcriptPath;
        var ctxCfg = config.getContext();
        // 从配置中提取三级压缩阈值和参数，构建压缩引擎
        this.compressionEngine = new CompressionEngine(
                ctxCfg.getCompression().getSnipThreshold(),       // Snip 触发水位
                ctxCfg.getCompression().getPruneThreshold(),      // Prune 触发水位
                ctxCfg.getCompression().getSummarizeThreshold(),  // Summarize 触发水位
                ctxCfg.getSnipKeepChars(),                        // Snip 保留的字符数
                ctxCfg.getSummarizeMaxInputChars(),               // Summarize 送入 LLM 的最大输入字符数
                ctxCfg.getSummarizeMaxOutputChars(),              // Summarize LLM 生成的最大输出字符数
                llmClient,
                transcriptPath);
        this.pairingRepairer = new PairingRepairer();
    }

    @Override
    public HookResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        // 1. 计算当前上下文水位：已用 token 占最大 token 的比例
        long usedTokens = ctx.estimateTokens();
        long maxTokens = config.getContext().getMaxTokens();
        double waterLevel = Math.min(1.0, (double) usedTokens / maxTokens);
        state.getCompressionState().setLastWaterLevel(waterLevel);

        // 2. 判断是否满足轮数触发条件：
        //    a) 距上次轮数压缩的对话轮数 >= 配置阈值（summarizeTurns）
        //    b) transcript 中有足够多的消息可供裁剪（超过尾部保护区所需的最小消息数）
        List<ChatMessage> transcript = ctx.getTranscript();
        int turnsSinceLastCompress = state.getTurnCount() - state.getCompressionState().getLastTurnCompressed();
        boolean turnTriggered = turnsSinceLastCompress >= config.getSummarizeTurns()
                && transcript != null && transcript.size() > config.getContext().getTailGuardMinTurns() * 2 + 2;

        // 3. 判断是否满足水位触发条件：水位达到 Snip 阈值
        boolean waterTriggered = waterLevel >= config.getContext().getCompression().getSnipThreshold();

        // 4. 两个触发条件都不满足，无需压缩，直接返回
        if (!waterTriggered && !turnTriggered) {
            return HookResult.ok();
        }

        // 5. transcript 为空或不存在，无可压缩内容
        if (transcript == null || transcript.isEmpty()) return HookResult.ok();

        CompressionState cs = state.getCompressionState();
        int tailGuardTurns = config.getContext().getTailGuardMinTurns();

        // 6. 创建 transcript 副本，避免直接修改原始列表导致副作用
        List<ChatMessage> workingCopy = new ArrayList<>(transcript);

        if (waterTriggered) {
            // 7a. 水位触发：执行完整三级递进压缩流程 Snip → Prune → Summarize
            //     CompressionEngine 内部根据 waterLevel 自动判断执行到哪一级
            compressionEngine.compress(workingCopy, cs, waterLevel, tailGuardTurns);
        } else {
            // 7b. 轮数触发：仅执行轻量压缩 Snip
            //     轮数触发的目的是定期清理过长的工具结果，避免上下文缓慢膨胀
            compressionEngine.compressByTurnCount(workingCopy, cs, tailGuardTurns);
        }

        // 8. 若执行了 Summarize（删除了旧消息），需要修复 tool_use/tool_result 配对断裂
        //    判断依据：summarizedUpToIndex >= 0 表示曾执行过 Summarize，
        //    且索引仍在 workingCopy 范围内，且是水位触发路径（轮数触发不做 Summarize）
        boolean didSummarize = cs.getSummarizedUpToIndex() >= 0
                && cs.getSummarizedUpToIndex() < workingCopy.size()
                && waterTriggered;
        if (didSummarize) {
            // PairingRepairer 会：丢弃孤立的 tool_result，为缺失结果的 tool_use 插入合成错误消息
            workingCopy = pairingRepairer.repair(workingCopy);
        }

        // 9. 将压缩后的 transcript 写回 ContextBuilder
        ctx.setTranscript(workingCopy);

        // 10. 记录本次轮数压缩的 turnCount，作为下次轮数触发判断的基准
        if (turnTriggered) {
            state.getCompressionState().setLastTurnCompressed(state.getTurnCount());
        }

        // 11. 压缩后重新估算 token 数，用于日志对比
        long postCompressTokens = ctx.estimateTokens();
        String trigger = waterTriggered ? "water " + String.format("%.0f%%", waterLevel * 100) : "turn " + state.getTurnCount();
        log.info("[上下文压缩] {} | {} -> {} 条消息 | 估算 {} -> {} tokens",
                trigger, transcript.size(), workingCopy.size(), usedTokens, postCompressTokens);

        return HookResult.ok();
    }
}
