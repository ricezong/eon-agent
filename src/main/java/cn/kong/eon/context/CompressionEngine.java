package cn.kong.eon.context;

import cn.kong.eon.model.CompressionState;
import dev.langchain4j.data.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 压缩引擎。
 * 对应技术方案第 6.2 节。
 * 三级压缩：Snip（Tier 1）→ Prune（Tier 2）→ Summarize（Tier 3）。
 * 压缩决策单调推进——同一消息一旦被 Snip，不会回退为完整。
 */
public class CompressionEngine {
    private static final Logger log = LoggerFactory.getLogger(CompressionEngine.class);

    private static final Pattern REF_PATTERN = Pattern.compile("artifact://(art_\\d+)");

    private final double snipThreshold;
    private final double pruneThreshold;
    private final int snipKeepChars;
    private final int pruneKeepChars;

    public CompressionEngine(double snipThreshold, double pruneThreshold,
                             int snipKeepChars, int pruneKeepChars) {
        this.snipThreshold = snipThreshold;
        this.pruneThreshold = pruneThreshold;
        this.snipKeepChars = snipKeepChars;
        this.pruneKeepChars = pruneKeepChars;
    }

    /**
     * 根据水位执行压缩。
     * 返回压缩后的消息列表（修改后的副本）。
     */
    public List<ChatMessage> compress(List<ChatMessage> messages,
                                      CompressionState state,
                                      double waterLevel,
                                      int tailGuardTurns) {
        if (waterLevel >= pruneThreshold) {
            log.info("Water level {} >= {}, applying Prune (Tier 2)", waterLevel, pruneThreshold);
            applyPrune(messages, state, tailGuardTurns);
        } else if (waterLevel >= snipThreshold) {
            log.info("Water level {} >= {}, applying Snip (Tier 1)", waterLevel, snipThreshold);
            applySnip(messages, state, tailGuardTurns);
        }
        return messages;
    }

    /**
     * Snip（Tier 1）：阅后即焚，截短 tool result 内容。
     * 保留 tool_call_id 骨架，content 替换为摘要。
     */
    private void applySnip(List<ChatMessage> messages, CompressionState state, int tailGuardTurns) {
        // 计算尾部保护区起始位置（最近 N 轮不压缩）
        int tailStart = Math.max(0, messages.size() - tailGuardTurns * 2 - 2);

        for (int i = 0; i < tailStart && i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof ToolExecutionResultMessage trm) {
                if (state.isSnipped(trm.id())) continue;

                String content = trm.text();
                if (content == null || content.length() <= snipKeepChars) continue;

                String refId = extractRef(content);
                String snippet = content.substring(0, Math.min(snipKeepChars, content.length()));
                if (refId != null) {
                    snippet += "... [Tool result trimmed: kept summary only. Ref: " + refId + "]";
                } else {
                    snippet += "... [Tool result trimmed: kept summary only]";
                }

                messages.set(i, ToolExecutionResultMessage.from(trm.id(), trm.toolName(), snippet));
                state.markSnipped(trm.id());
                log.debug("Snipped tool result: {} ({} -> {} chars)", trm.id(), content.length(), snippet.length());
            }
        }
    }

    /**
     * Prune（Tier 2）：更激进的阅后即焚，替换为占位符。
     */
    private void applyPrune(List<ChatMessage> messages, CompressionState state, int tailGuardTurns) {
        int tailStart = Math.max(0, messages.size() - tailGuardTurns * 2 - 2);

        for (int i = 0; i < tailStart && i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof ToolExecutionResultMessage trm) {
                if (state.isPruned(trm.id())) continue;

                String content = trm.text();
                String refId = extractRef(content);
                String placeholder;
                if (refId != null) {
                    placeholder = "[Old tool result content cleared. Ref: " + refId + "]";
                } else {
                    placeholder = "[Old tool result content cleared]";
                }

                messages.set(i, ToolExecutionResultMessage.from(trm.id(), trm.toolName(), placeholder));
                state.markPruned(trm.id());
                log.debug("Pruned tool result: {} ({} -> {} chars)", trm.id(), content.length(), placeholder.length());
            }
        }
    }

    /**
     * 从内容中提取 artifact refId。
     */
    private String extractRef(String content) {
        if (content == null) return null;
        Matcher m = REF_PATTERN.matcher(content);
        return m.find() ? m.group(1) : null;
    }
}
