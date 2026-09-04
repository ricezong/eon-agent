package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.TextTrimmer;
import cn.kong.eon.agent.context.block.CompressionLevel;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.CompressionState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 压缩机制的策略编排者。每轮在 PreModel 阶段执行一次，流程固定为四步：
 * 判档位 → 处置 → 修复配对 → 返回档位。
 * <p>
 * 档位是三级阶梯 + 轮数兜底：水位自上而下比较，命中即返回（"水位优先"由此成为
 * 结构上的保证，而不是靠调用顺序的约定）；轮次序号为周期整数倍且水位三档均未命中时，
 * 执行一个固定的轻量档位，使上下文在没有冲高水位的情况下也能逐步收敛。
 * <p>
 * <b>处置范围按位置划定</b>：保护区起点之前的块才可处置，被 pin 的块（当前用户输入）
 * 恒在保护区内。块类型不决定"能否压缩"，只决定"怎么压缩"。
 * <p>
 * SNIP / PRUNE 两档在本类内就地处置块（截断、清空、参数骨架化）；
 * SUMMARIZE 档委托 {@link ContextSummarizer} 生成摘要，顺序是"先摘要、后删除"——
 * 摘要生成成功写入状态之后才移除原文，否则一旦摘要失败就会同时失去原文与摘要。
 * <p>
 * 运行参数直接来自 {@link AgentConfig.ContextConfig}。
 */
public class CompressionPolicy {

    /** 参数中超过该长度的字符串字段才被视为大字段并替换。 */
    private static final int LONG_FIELD_CHARS = 200;

    private final AgentConfig.ContextConfig config;
    private final ObjectMapper objectMapper;
    private final ContextSummarizer summarizer;

    public CompressionPolicy(AgentConfig.ContextConfig config,
                             ObjectMapper objectMapper,
                             ContextSummarizer summarizer) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.summarizer = summarizer;
    }

    /**
     * 执行本轮压缩。
     *
     * @param window    上下文窗口，就地修改
     * @param metrics   当前度量，用于判定档位
     * @param state     压缩状态，摘要存放处
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
            disposed = window.removeBefore(protectedFrom);
            // 删除块会切断 tool_use / tool_result 配对，由窗口自动修复
            window.repairPairing();
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
     * <p>
     * 遍历按下标进行，下标本身就是"是否在保护区内"的判定。被 pin 的块额外跳过：
     * 它恒在保护区之内，这里多一道保险，防止将来保护区算法被改动后误伤当前诉求。
     *
     * @return 是否处置了至少一个块
     */
    private boolean dispose(ContextWindow window, CompressionLevel level, int protectedFrom) {
        boolean disposed = false;
        List<ContextBlock> blocks = window.blocks();
        int limit = Math.min(protectedFrom, blocks.size());

        for (int i = 0; i < limit; i++) {
            ContextBlock block = blocks.get(i);
            if (block.isPinned() || block.disposedAtOrAbove(level)) continue;

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
            case TOOL_ARGS -> argsReplacement(block, level);
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
            return clearedPlaceholder(block);
        }
        return headTailPlaceholder(block, config.getSnipKeepChars());
    }

    /**
     * 工具参数的替换文本。仅在档位不低于 PRUNE、磁盘有副本、且超过最小字符数时骨架化。
     * 参数是 JSON 字符串，截断与占位符都会产出非法 JSON 导致请求被拒收，
     * 因此这里只有骨架化一条路，条件不满足就不处置。
     */
    private String argsReplacement(ContextBlock block, CompressionLevel level) {
        if (!level.atLeast(CompressionLevel.PRUNE)) return null;
        if (!block.recoverable() || block.chars() <= config.getCompression().getOffloadMinChars()) return null;
        return skeletonPlaceholder(block);
    }

    // ═══════════════════ 占位文本生成 ═══════════════════

    /**
     * 头尾保留截断：保留开头与结尾，中段以省略号替代。
     * 返回 null 一律表示"这个块不适合这样替换"，调用方据此跳过。
     */
    private static String headTailPlaceholder(ContextBlock block, int keepChars) {
        String raw = block.text();
        if (raw.length() <= keepChars) return null;
        return TextTrimmer.headTail(raw, keepChars);
    }

    /**
     * 清空：把整块内容替换为一行占位文本。
     * 磁盘上有副本时占位文本带上引用，模型可据此取回原文。
     * 本方法仅服务于 TOOL_RESULT 块（清空无副本的销毁权留给 SUMMARIZE 档）。
     */
    private static String clearedPlaceholder(ContextBlock block) {
        return block.refId() != null
                ? "[旧工具结果内容已清除。完整内容已保存至 artifact://" + block.refId()
                + "，可用 read_file 工具读取]"
                : "[旧工具结果内容已清除]";
    }

    /**
     * 参数骨架化：把参数 JSON 中的长字符串字段替换为一行说明，保留其余字段与结构。
     * 输出必须是严格合法的 JSON——这段文本会作为历史工具调用的 arguments 原样回传给模型，
     * 供应商会校验该字段格式，不合法会直接拒收整个请求。
     * <p>
     * 落盘位置由入站规则 {@code ToolArgsRecoverRule} 在标记 recoverable 时盖印到
     * {@code block.refId()}，本方法直接读取，不再猜测参数键名。
     *
     * @return 骨架 JSON；参数无法解析、不含长字段或序列化失败时返回 null，
     *         调用方必须据此放弃替换——宁可多占 token，也不能发出会被拒收的请求
     */
    private String skeletonPlaceholder(ContextBlock block) {
        Map<String, Object> args = parseArgs(block.text());
        if (args.isEmpty()) return null;

        String location = block.refId();
        String note = location != null
                ? "内容已完整落盘至 " + location + "，可用 read_file 读取"
                : "内容已落盘，可用 read_file 读取";

        Map<String, Object> slim = new LinkedHashMap<>();
        boolean replacedAny = false;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s && s.length() > LONG_FIELD_CHARS) {
                slim.put(entry.getKey(), "<" + s.length() + " 字符已清空：" + note + ">");
                replacedAny = true;
            } else {
                slim.put(entry.getKey(), value);
            }
        }
        if (!replacedAny) return null;

        try {
            return objectMapper.writeValueAsString(slim);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
