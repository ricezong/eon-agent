package cn.kong.eon.context;

import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.model.CompressionState;
import dev.langchain4j.data.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 压缩引擎。三级递进压缩，决策单调推进：
 *   Snip (Tier 1) — 截短旧 tool result，保留骨架 + 摘要前缀
 *   Prune (Tier 2) — 替换为占位符（隐含 Snip）
 *   Summarize (Tier 3) — LLM 生成摘要，删除旧消息（会破坏配对，需 PairingRepairer 修复）
 */
public class CompressionEngine {
    private static final Logger log = LoggerFactory.getLogger(CompressionEngine.class);

    private static final Pattern REF_PATTERN = Pattern.compile("artifact://(art_\\d+)");

    private final double snipThreshold;
    private final double pruneThreshold;
    private final double summarizeThreshold;
    private final int snipKeepChars;
    private final int pruneKeepChars;
    private final int summarizeMaxInputChars;
    private final int summarizeMaxOutputChars;
    private final LlmClient llmClient;

    public CompressionEngine(double snipThreshold, double pruneThreshold,
                             double summarizeThreshold,
                             int snipKeepChars, int pruneKeepChars,
                             int summarizeMaxInputChars, int summarizeMaxOutputChars,
                             LlmClient llmClient) {
        this.snipThreshold = snipThreshold;
        this.pruneThreshold = pruneThreshold;
        this.summarizeThreshold = summarizeThreshold;
        this.snipKeepChars = snipKeepChars;
        this.pruneKeepChars = pruneKeepChars;
        this.summarizeMaxInputChars = summarizeMaxInputChars;
        this.summarizeMaxOutputChars = summarizeMaxOutputChars;
        this.llmClient = llmClient;
    }

    /**
     * 根据水位执行压缩：先 Snip → 再 Prune → 最后 Summarize。
     *
     * 注意：此方法直接修改传入的 messages 列表（原地操作）。
     * 调用方应在调用前创建副本（如 new ArrayList<>(transcript)），
     * 以确保原始账本（JsonlStore）不受影响。
     *
     * @return 传入的 messages 列表（已压缩）
     */
    public List<ChatMessage> compress(List<ChatMessage> messages,
                                      CompressionState state,
                                      double waterLevel,
                                      int tailGuardTurns) {
        if (waterLevel >= summarizeThreshold) {
            log.info("[Compress] water={} >= {} -> Tier 1+2+3 (Snip+Prune+Summarize)",
                    String.format("%.2f", waterLevel), summarizeThreshold);
            applyPrune(messages, state, tailGuardTurns);
            applySummarize(messages, state, tailGuardTurns);
        } else if (waterLevel >= pruneThreshold) {
            log.info("[Compress] water={} >= {} -> Tier 2 (Prune)",
                    String.format("%.2f", waterLevel), pruneThreshold);
            applyPrune(messages, state, tailGuardTurns);
        } else if (waterLevel >= snipThreshold) {
            log.info("[Compress] water={} >= {} -> Tier 1 (Snip)",
                    String.format("%.2f", waterLevel), snipThreshold);
            applySnip(messages, state, tailGuardTurns);
        }
        return messages;
    }

    /** Snip：截短 tool result，保留骨架 + 摘要前缀。 */
    private void applySnip(List<ChatMessage> messages, CompressionState state, int tailGuardTurns) {
        int tailStart = Math.max(0, messages.size() - tailGuardTurns * 2 - 2);
        int snipCount = 0;
        int totalBefore = 0;
        int totalAfter = 0;

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
                log.debug("[Compress] Snip: {} ({} -> {} chars)", trm.id(), content.length(), snippet.length());
                snipCount++;
                totalBefore += content.length();
                totalAfter += snippet.length();
            }
        }
        if (snipCount > 0) {
            log.info("[Compress] Snip: trimmed {} tool results ({} -> {} chars)",
                    snipCount, totalBefore, totalAfter);
        }
    }

    /** Prune：替换 tool result 为占位符。 */
    private void applyPrune(List<ChatMessage> messages, CompressionState state, int tailGuardTurns) {
        int tailStart = Math.max(0, messages.size() - tailGuardTurns * 2 - 2);
        int pruneCount = 0;

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
                log.debug("[Compress] Prune: {} ({} -> {} chars)", trm.id(), content.length(), placeholder.length());
                pruneCount++;
            }
        }
        if (pruneCount > 0) {
            log.info("[Compress] Prune: replaced {} tool results with placeholder", pruneCount);
        }
    }

    /**
     * Summarize：LLM 生成摘要，删除被覆盖的旧消息。
     * 删除旧消息会导致配对断裂，由 ContextCompactor 调用 PairingRepairer 修复。
     */
    private void applySummarize(List<ChatMessage> messages, CompressionState state, int tailGuardTurns) {
        int tailStart = Math.max(0, messages.size() - tailGuardTurns * 2 - 2);

        if (tailStart <= 0) {
            log.debug("[Compress] Summarize skipped: no messages outside tail guard");
            return;
        }

        if (state.getSummarizedUpToIndex() >= tailStart) {
            log.debug("[Compress] Summarize skipped: already summarized up to index {}", state.getSummarizedUpToIndex());
            return;
        }

        // 拼接旧消息文本
        StringBuilder dialogText = new StringBuilder();
        for (int i = 0; i < tailStart; i++) {
            String line = formatMessageForSummary(messages.get(i));
            if (line != null && !line.isBlank()) {
                dialogText.append(line).append("\n");
                if (dialogText.length() >= summarizeMaxInputChars) {
                    dialogText.append("... [truncated]\n");
                    break;
                }
            }
        }

        if (dialogText.isEmpty()) {
            log.debug("[Compress] Summarize skipped: no dialog text to summarize");
            return;
        }

        String summaryPrompt = """
                请将以下历史对话压缩为一段简洁摘要，保留以下要点：
                - 用户的核心请求和目标
                - 已完成的关键步骤和决策
                - 工具调用的关键发现和结果要点
                - 尚未完成的任务和下一步计划

                摘要要求：
                - 不超过 %d 个字符
                - 用陈述句，不用对话格式
                - 保留关键事实和数字，省略过程性描述

                === 历史对话 ===
                %s
                """.formatted(summarizeMaxOutputChars, dialogText);

        List<ChatMessage> summaryMessages = new ArrayList<>();
        summaryMessages.add(SystemMessage.from("你是一个对话摘要生成器。请严格按指令生成摘要。"));
        summaryMessages.add(UserMessage.from(summaryPrompt));

        try {
            LlmResponse response = llmClient.chat(summaryMessages, null);
            String summary = response.aiMessage().text();

            if (summary == null || summary.isBlank()) {
                log.warn("[Compress] Summarize: LLM returned empty summary, skipping");
                return;
            }

            if (summary.length() > summarizeMaxOutputChars) {
                summary = summary.substring(0, summarizeMaxOutputChars) + "...";
            }

            // 追加合并已有摘要
            String existingSummary = state.getLastSummary();
            if (existingSummary != null && !existingSummary.isBlank()) {
                summary = existingSummary + "\n\n" + summary;
            }
            state.setLastSummary(summary);

            // 删除已被摘要覆盖的旧消息
            int removeCount = tailStart;
            messages.subList(0, removeCount).clear();

            state.setSummarizedUpToIndex(tailStart);

            String summaryPreview = summary.length() > 200 ? summary.substring(0, 200) + "..." : summary;
            log.info("[Compress] Summarize: removed {} msgs | summary: {} chars | preview: \"{}\"",
                    removeCount, summary.length(), summaryPreview);

        } catch (Exception e) {
            log.error("[Compress] Summarize failed: {} → 降级为 Prune 旧消息", e.getMessage());
            // 降级策略：删除已被覆盖的旧消息，防止上下文继续膨胀
            int removeCount = tailStart;
            messages.subList(0, removeCount).clear();
            state.setSummarizedUpToIndex(tailStart);
            log.info("[Compress] Fallback Prune: removed {} msgs (summarize failed)", removeCount);
        }
    }

    private String formatMessageForSummary(ChatMessage msg) {
        if (msg instanceof SystemMessage sm) {
            return null;
        }
        if (msg instanceof UserMessage um) {
            return "[用户] " + um.singleText();
        }
        if (msg instanceof AiMessage am) {
            String text = am.text();
            if (text != null && !text.isBlank()) {
                return "[助手] " + text;
            }
            return null;
        }
        if (msg instanceof ToolExecutionResultMessage trm) {
            String content = trm.text();
            if (content != null && !content.isBlank()) {
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "...";
                }
                return "[工具结果:" + trm.toolName() + "] " + content;
            }
            return null;
        }
        return null;
    }

    private String extractRef(String content) {
        if (content == null) return null;
        Matcher m = REF_PATTERN.matcher(content);
        return m.find() ? m.group(1) : null;
    }
}
