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
 * 压缩引擎。三级递进压缩：
 * Snip — 截短旧 tool result，保留骨架 + 摘要前缀。
 * Prune — 替换为占位符（隐含 Snip）。
 * Summarize — LLM 生成摘要，删除旧消息（会破坏配对，需 PairingRepairer 修复）。
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
    private final String transcriptPath;

    public CompressionEngine(double snipThreshold, double pruneThreshold,
                             double summarizeThreshold,
                             int snipKeepChars, int pruneKeepChars,
                             int summarizeMaxInputChars, int summarizeMaxOutputChars,
                             LlmClient llmClient,
                             String transcriptPath) {
        this.snipThreshold = snipThreshold;
        this.pruneThreshold = pruneThreshold;
        this.summarizeThreshold = summarizeThreshold;
        this.snipKeepChars = snipKeepChars;
        this.pruneKeepChars = pruneKeepChars;
        this.summarizeMaxInputChars = summarizeMaxInputChars;
        this.summarizeMaxOutputChars = summarizeMaxOutputChars;
        this.llmClient = llmClient;
        this.transcriptPath = transcriptPath != null ? transcriptPath : "(transcript path unavailable)";
    }

    /**
     * 根据水位执行压缩：先 Snip → 再 Prune → 最后 Summarize。原地修改 messages 列表。
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

    /**
     * 轮数触发压缩：仅 Snip（截短过长工具结果），不执行 Summarize。
     * Summarize 是昂贵的 LLM 调用，仅在水位达到 summarize_threshold 时由 water-triggered 路径触发。
     * 如果水位已达 prune 阈值，额外执行 Prune。
     */
    public List<ChatMessage> compressByTurnCount(List<ChatMessage> messages,
                                                   CompressionState state,
                                                   double waterLevel,
                                                   int tailGuardTurns) {
        log.info("[Compress] turn-triggered (water={}) -> Snip{}",
                String.format("%.2f", waterLevel),
                waterLevel >= pruneThreshold ? " + Prune" : "");
        if (waterLevel >= snipThreshold) {
            applySnip(messages, state, tailGuardTurns);
        }
        if (waterLevel >= pruneThreshold) {
            applyPrune(messages, state, tailGuardTurns);
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
                    snippet += "... [工具结果已截断：仅保留摘要。引用: " + refId + "]";
                } else {
                    snippet += "... [工具结果已截断：仅保留摘要]";
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
                    placeholder = "[旧工具结果内容已清除。引用: " + refId + "]";
                } else {
                    placeholder = "[旧工具结果内容已清除]";
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
     * Summarize：LLM 生成 5 段式摘要，删除被覆盖的旧消息。
     * 增量摘要：旧摘要 + 被裁剪对话一起送 LLM 重生成（非字符串拼接）。
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

        // 拼接被裁剪的对话文本
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

        String existingSummary = state.getLastSummary();
        String existingSummarySection = (existingSummary != null && !existingSummary.isBlank())
                ? existingSummary
                : "(无旧摘要，首次生成)";

        // 5 段式摘要提示词（个人助手优化版）
        String summaryPrompt = """
                请将以下历史对话压缩为结构化摘要，严格按以下 5 段格式输出：

                1. Primary Request and Intent — 用户的核心诉求（逐条列出）
                2. Key Context and Decisions — 关键上下文、已做的决策、已获取的关键信息
                3. User Preferences and Updates — 本轮中发现/更新/确认的用户偏好和记忆
                4. Pending Tasks and Current Work — 未完成任务与当前进展
                5. All User Messages and Transcript — 用户原始消息（逐条）+ 原始记录文件路径与回溯指引

                第 5 段必须包含（固定模板）：
                "原始对话完整记录: %s
                当任务或状态不清楚时，请用搜索工具检索该文件而不是猜测。
                回溯方法：先按关键词（任务名/文件名/ID/错误信息/工具名）定位匹配行，
                再用读取文件工具查看匹配位置附近的内容，还原意图和状态；
                不要从头到尾通读整个文件（文件可能非常大）。"

                摘要要求：不超过 %d 字符；陈述句；保留关键事实与数字；省略过程性描述。
                如有旧摘要，请合并旧摘要与新对话生成新版摘要（增量），不要直接拼接。

                === 旧摘要（如有） ===
                %s

                === 本次被裁剪的对话 ===
                %s
                """.formatted(transcriptPath, summarizeMaxOutputChars, existingSummarySection, dialogText);

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

            // 增量摘要：整体替换（旧摘要 + 被裁剪对话一起送 LLM 重生成的新版摘要）
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
