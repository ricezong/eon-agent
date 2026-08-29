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
import cn.kong.eon.store.JsonlStore;
import dev.langchain4j.data.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩（PreModel, order=100）。
 * 三级递进：≥snip 截短工具结果 → ≥prune 替换为占位符 → ≥summarize LLM 摘要并删旧消息。
 * Summarize 后执行 PairingRepairer 修复配对断裂。
 * 压缩结果写回 JsonlStore 内存视图，跨轮持久生效（磁盘账本保持完整不受影响）。
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
    private final JsonlStore jsonlStore;

    /**
     * 构造函数：从 AgentConfig 中读取压缩相关配置，初始化 CompressionEngine 和 PairingRepairer。
     *
     * @param config        Agent 全局配置，包含压缩阈值、尾部保护轮数等参数
     * @param llmClient     LLM 客户端，供 CompressionEngine 在 Summarize 阶段调用 LLM 生成摘要
     * @param transcriptPath 原始对话记录文件路径，嵌入摘要提示词中供后续回溯
     * @param jsonlStore    消息存储，压缩结果写回其内存视图实现跨轮持久化
     */
    public ContextCompactHook(AgentConfig config, LlmClient llmClient, String transcriptPath, JsonlStore jsonlStore) {
        this.config = config;
        this.jsonlStore = jsonlStore;
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
        // 1. transcript 为空或不存在，无可压缩内容，后续水位/轮数判断均无意义
        List<ChatMessage> transcript = ctx.getTranscript();
        if (transcript == null || transcript.isEmpty()) return HookResult.ok();

        // 2. 计算当前上下文水位：已用 token 占最大 token 的比例
        long usedTokens = ctx.estimateTokens();
        long maxTokens = config.getContext().getMaxTokens();
        double waterLevel = Math.min(1.0, (double) usedTokens / maxTokens);
        state.getCompressionState().setLastWaterLevel(waterLevel);

        // 3. 判断是否满足轮数触发条件：
        //    a) 距上次轮数压缩的对话轮数 >= 配置阈值（summarizeTurns）
        //    b) transcript 中有足够多的消息可供裁剪（超过尾部保护区所需的最小消息数）
        int turnsSinceLastCompress = state.getTurnCount() - state.getCompressionState().getLastTurnCompressed();
        boolean turnTriggered = turnsSinceLastCompress >= config.getSummarizeTurns()
                && transcript.size() > config.getContext().getTailGuardMinTurns() * 2 + 2;

        // 4. 判断是否满足水位触发条件：水位达到 Snip 阈值
        boolean waterTriggered = waterLevel >= config.getContext().getCompression().getSnipThreshold();

        // 5. 两个触发条件都不满足，无需压缩，直接返回
        if (!waterTriggered && !turnTriggered) {
            return HookResult.ok();
        }

        CompressionState cs = state.getCompressionState();
        int tailGuardTurns = config.getContext().getTailGuardMinTurns();

        // 6. 创建 transcript 副本，避免直接修改原始列表导致副作用
        List<ChatMessage> workingCopy = new ArrayList<>(transcript);

        // 7. 统一压缩入口：无论轮数触发还是水位触发，都走同一个 compress() 方法。
        //    轮数触发时，使用 snipThreshold 作为有效水位，仅驱动 Snip 级压缩；
        //    水位触发时，使用真实水位，由 compress() 内部决定压缩到哪一级（累积递进）。
        //    这消除了原来两套独立压缩路径的重复逻辑。
        double effectiveWaterLevel = waterTriggered ? waterLevel : config.getContext().getCompression().getSnipThreshold();

        // 记录压缩前的消息数，用于判断 Summarize 是否在本轮执行（删除了消息）
        int preCompressSize = workingCopy.size();

        compressionEngine.compress(workingCopy, cs, effectiveWaterLevel, tailGuardTurns);

        // 8. 若本轮执行了 Summarize（删除了旧消息），需要修复 tool_use/tool_result 配对断裂
        //    判断依据：本轮压缩后消息数减少（Summarize 会删除旧消息，Snip/Prune 只替换不删除）
        boolean didSummarize = workingCopy.size() < preCompressSize;
        if (didSummarize) {
            // PairingRepairer 会：丢弃孤立的 tool_result，为缺失结果的 tool_use 插入合成错误消息
            workingCopy = pairingRepairer.repair(workingCopy);
        }

        // 9. 压缩结果持久化：写回 JsonlStore 内存视图（磁盘账本不受影响）。
        //    下一轮 buildContext 取到的 snapshot 即为压缩后视图，
        //    压缩不再每轮重做，水位也随之回落；同步更新本轮 ContextBuilder。
        jsonlStore.replaceAll(workingCopy);
        ctx.setTranscript(workingCopy);

        // 10. 记录本次压缩的 turnCount，作为下次轮数触发判断的基准
        //     无论哪种触发方式，都更新基准，因为两种路径都会执行 Snip 压缩
        state.getCompressionState().setLastTurnCompressed(state.getTurnCount());

        // 11. 压缩后重新估算 token 数，用于日志对比
        long postCompressTokens = ctx.estimateTokens();
        String trigger = waterTriggered ? "water " + String.format("%.0f%%", waterLevel * 100) : "turn " + state.getTurnCount();
        log.info("[上下文压缩] {} | {} -> {} 条消息 | 估算 {} -> {} tokens",
                trigger, transcript.size(), workingCopy.size(), usedTokens, postCompressTokens);

        return HookResult.ok();
    }
}
