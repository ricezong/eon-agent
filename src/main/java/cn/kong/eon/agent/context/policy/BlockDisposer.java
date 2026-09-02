package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.block.CompressionLevel;
import cn.kong.eon.agent.context.block.ContextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 块处置器。按档位遍历窗口，把块内容替换为更短的占位文本。
 * <p>
 * 工具结果与工具参数在同一条遍历里处理，区别只有两点：哪个档位会触发它、
 * 替换文本由 {@link Placeholders} 的哪个方法生成。
 * <p>
 * 档位是阶梯：高档位会重新处置低档位已经处理过的块，反之不会。
 * 用 {@link ContextBlock#disposedAtOrAbove(CompressionLevel)} 表达——
 * 已接受过不低于本档位处置的块直接跳过。
 */
public class BlockDisposer {
    private static final Logger log = LoggerFactory.getLogger(BlockDisposer.class);

    private final CompressionSettings settings;
    private final ObjectMapper objectMapper;

    public BlockDisposer(CompressionSettings settings, ObjectMapper objectMapper) {
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    /**
     * 按档位处置窗口中的块。
     *
     * @param window     上下文窗口，就地修改
     * @param level      本轮档位，必须是 SNIP 或 PRUNE
     * @param cutoffTurn 尾部保护区起始轮次，turn 不小于此值的块不被处置
     * @return 被替换的块数
     */
    public int dispose(ContextWindow window, CompressionLevel level, int cutoffTurn) {
        int replaced = 0;
        int candidate = 0;
        long charsBefore = window.totalChars();

        for (ContextBlock block : window.blocks()) {
            if (!inScope(block, level, cutoffTurn)) continue;
            candidate++;

            String replacement = replacementFor(block, level);
            if (replacement == null || replacement.length() >= block.chars()) continue;

            block.setText(replacement);
            block.markDisposed(level);
            replaced++;
        }

        if (replaced > 0) {
            log.info("[压缩] {}: 替换 {}/{} 个候选块 ({} -> {} 字符)",
                    level, replaced, candidate, charsBefore, window.totalChars());
        } else if (candidate > 0) {
            log.debug("[压缩] {}: {} 个候选块均无更短的替换文本，跳过", level, candidate);
        }
        return replaced;
    }

    /** 块是否在本档位的处置范围内：可改写、在保护区外、且未接受过不低于本档位的处置。 */
    private boolean inScope(ContextBlock block, CompressionLevel level, int cutoffTurn) {
        return block.retention().compressible()
                && block.turn() < cutoffTurn
                && !block.disposedAtOrAbove(level);
    }

    private String replacementFor(ContextBlock block, CompressionLevel level) {
        return switch (block.kind()) {
            case TOOL_RESULT -> resultReplacement(block, level);
            case TOOL_ARGS -> argsReplacement(block, level);
            default -> null;
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
            return Placeholders.cleared(block);
        }
        return Placeholders.headTail(block, settings.snipKeepChars());
    }

    /**
     * 工具参数的替换文本。仅在档位不低于 PRUNE、磁盘有副本、且超过最小字符数时骨架化。
     * 参数是 JSON 字符串，截断与占位符都会产出非法 JSON 导致请求被拒收，
     * 因此这里只有骨架化一条路，条件不满足就不处置。
     */
    private String argsReplacement(ContextBlock block, CompressionLevel level) {
        if (!level.atLeast(CompressionLevel.PRUNE)) return null;
        if (!block.recoverable() || block.chars() <= settings.offloadMinChars()) return null;
        return Placeholders.skeleton(block, objectMapper);
    }
}
