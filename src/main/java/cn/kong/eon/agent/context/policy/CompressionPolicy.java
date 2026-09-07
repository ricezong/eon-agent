package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContentCompressor;
import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.block.CompressionLevel;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.CompressionState;

import java.util.List;

/**
 * 压缩机制的策略编排者。每轮在 PreModel 阶段执行一次，流程固定为四步：
 * 判档位 → 处置 → 修复配对 → 返回档位。
 * <p>
 * 档位是三级阶梯 + 轮数兜底：水位自上而下比较，命中即返回（"水位优先"由此成为
 * 结构上的保证，而不是靠调用顺序的约定）；轮次序号为周期整数倍且水位三档均未命中时，
 * 执行一个固定的轻量档位，使上下文在没有冲高水位的情况下也能逐步收敛。
 * <p>
 * <b>处置范围按位置划定</b>：窗口末尾保护区之外的块才可处置。
 * 块类型不决定"能否压缩"，只决定"怎么压缩"。
 * <p>
 * SNIP / PRUNE 两档在本类内就地处置块（截断、清空、参数裁剪）；
 * SUMMARIZE 档委托 {@link ContextSummarizer} 生成摘要，顺序是"先摘要、后删除"——
 * 摘要生成成功写入状态之后才移除原文，否则一旦摘要失败就会同时失去原文与摘要。
 * SUMMARIZE 删除后还会把回放水位线（第一个幸存块的消息序号）写进压缩状态，
 * 会话恢复据此从正确的位置回放账本。
 * <p>
 * 运行参数直接来自 {@link AgentConfig.ContextConfig}。
 */
public class CompressionPolicy {

    private final AgentConfig.ContextConfig config;
    private final ContentCompressor compressor;
    private final ContextSummarizer summarizer;

    public CompressionPolicy(AgentConfig.ContextConfig config,
                             ContentCompressor compressor,
                             ContextSummarizer summarizer) {
        this.config = config;
        this.compressor = compressor;
        this.summarizer = summarizer;
    }

    /**
     * 执行本轮压缩。
     *
     * @param window    上下文窗口，就地修改
     * @param metrics   当前度量，用于判定档位
     * @param state     压缩状态，摘要与回放水位线存放处
     * @param turnCount 当前轮次序号，用于判定轮数入口
     * @return 本轮实际生效的档位；未命中档位或无块可处置时返回 {@link CompressionLevel#NONE}
     */
    public CompressionLevel apply(ContextWindow window, ContextMetrics metrics, CompressionState state, int turnCount) {
        CompressionLevel level = resolveLevel(metrics, turnCount);
        if (!level.enabled()) {
            return CompressionLevel.NONE;
        }

        // 尾部保护区
        int protectedFrom = window.protectedFrom(config.getCompression().getTailGuardBlocks());

        boolean disposed;
        if (level == CompressionLevel.SUMMARIZE) {
            String summary = summarizer.summarize(window, protectedFrom, state.getLastSummary());
            if (summary == null) {
                return CompressionLevel.NONE;
            }
            state.setLastSummary(summary);
            int keepFrom = window.removeBefore(protectedFrom);
            // 删除块会切断 tool_use / tool_result 配对，由窗口自动修复
            window.repairPairing();
            // 摘要与水位线成对更新：摘要覆盖 #0~keepFrom-1，账本保留 #keepFrom~，拼起来内容完整。
            // 窗口被清空时 keepFrom=-1，保留旧水位线——回放偏多与旧摘要重复，重复无害。
            if (keepFrom >= 0) {
                state.setKeepFromMessage(keepFrom);
            }
            disposed = true;
        } else {
            disposed = dispose(window, level, protectedFrom);
        }

        if (!disposed) {
            return CompressionLevel.NONE;
        }

        return level;
    }

    // ═══════════════════ 档位判定 ═══════════════════

    /** 水位三档自上而下命中即返回；均未命中时轮数入口兜底。 */
    private CompressionLevel resolveLevel(ContextMetrics metrics, int turnCount) {
        if (metrics == null) return CompressionLevel.NONE;

        double water = metrics.waterLevel();
        if (water >= config.getCompression().getSummarizeWaterLevel()) return CompressionLevel.SUMMARIZE;
        if (water >= config.getCompression().getPruneWaterLevel()) return CompressionLevel.PRUNE;
        if (water >= config.getCompression().getSnipWaterLevel()) return CompressionLevel.SNIP;

        if (turnCount > 0 && turnCount % config.getCompression().getTurnInterval() == 0) {
            return config.getCompression().getTurnLevel();
        }
        return CompressionLevel.NONE;
    }

    // ═══════════════════ 块处置（SNIP / PRUNE） ═══════════════════

    /**
     * 按档位遍历保护区之前的块，把块内容替换为更短的占位文本。
     * 档位是阶梯：高档位会重新处置低档位已经处理过的块，反之不会——
     * 已接受过不低于本档位处置的块直接跳过。
     *
     * @return 是否处置了至少一个块
     */
    private boolean dispose(ContextWindow window, CompressionLevel level, int protectedFrom) {
        boolean disposed = false;
        List<ContextBlock> blocks = window.blocks();
        int limit = Math.min(protectedFrom, blocks.size());

        for (int i = 0; i < limit; i++) {
            ContextBlock block = blocks.get(i);
            if (block.disposedAtOrAbove(level)) continue;

            String replacement = replacementFor(block, level);
            if (replacement == null || replacement.length() >= block.chars()) continue;

            block.setText(replacement);
            block.markDisposed(level);
            disposed = true;
        }

        return disposed;
    }

    private String replacementFor(ContextBlock block, CompressionLevel level) {
        return switch (block.kind()) {
            case TOOL_RESULT -> resultReplacement(block, level);
            case TOOL_ARGS -> argsPrune(block, level);
            // AI 正文只参与 SNIP 的头尾截断：推理链被清空比被截断更伤，
            // 截断至少保住开头的问题分析与结尾的结论。
            case AI_TEXT -> level == CompressionLevel.SNIP
                    ? headTailPlaceholder(block, config.getSnipKeepChars())
                    : null;
            // 用户消息只有"原文保留"与"被摘要吸收后删除"两个状态
            case USER_INPUT -> null;
            case OTHER -> headTailPlaceholder(block, config.getSnipKeepChars());
        };
    }

    /**
     * 工具结果的替换文本。
     * 磁盘有副本且档位不低于 PRUNE 时清空为占位符，否则头尾截断。
     * 无副本的块是唯一一份，清空后不可恢复，销毁权只留给 SUMMARIZE 档——
     * 先摘要出要点再删除，避免摘要模型面对空占位符。
     */
    private String resultReplacement(ContextBlock block, CompressionLevel level) {
        if (level.atLeast(CompressionLevel.PRUNE) && block.recoverable()) {
            return clearedPlaceholder();
        }
        return headTailPlaceholder(block, config.getSnipKeepChars());
    }

    /**
     * 工具参数裁剪：遍历参数 JSON 的字段，把过长的字符串字段替换为一行占位说明，
     * 其余字段与整体结构原样保留。<b>产出必须是合法 JSON</b>——这段文本会作为
     * arguments 原样回传给模型并接受格式校验，不合法会直接拒收整个请求，
     * 因此只有 {@link ContentCompressor#skeleton} 一条路，本类不做任何文本级截断。
     * <p>
     * 只在 PRUNE 档（水位 ≥80%）触发，且短参数不动——替换后反而变长的参数没有裁剪价值。
     * 裁剪是有损的：write 类工具的大参数在目标文件里另有原文，骨架保留 path 等短字段即可取回。
     */
    private String argsPrune(ContextBlock block, CompressionLevel level) {
        if (!level.atLeast(CompressionLevel.PRUNE)) return null;
        if (block.chars() <= config.getCompression().getArgsPruneMinChars()) return null;
        return compressor.skeleton(block.text());
    }

    // ═══════════════════ 占位文本生成 ═══════════════════

    /**
     * 头尾保留截断：保留开头与结尾，中段以省略号替代。
     * 返回 null 一律表示"这个块不适合这样替换"，调用方据此跳过。
     */
    private String headTailPlaceholder(ContextBlock block, int keepChars) {
        String raw = block.text();
        if (raw.length() <= keepChars) return null;
        return compressor.headTail(raw, keepChars);
    }

    /**
     * 清空：把整块内容替换为一行占位文本。
     * 取回路径不写在这里——块上的 refId 一直保留，渲染层据此输出引用，
     * 落盘后的截断与清空共用同一份指引。
     */
    private static String clearedPlaceholder() {
        return "[旧工具结果内容已清除]";
    }
}
