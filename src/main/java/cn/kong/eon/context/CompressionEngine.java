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
 * 压缩引擎。
 * 对应技术方案第 6.2 节。
 * 三级压缩：Snip（Tier 1）→ Prune（Tier 2）→ Summarize（Tier 3）。
 * 压缩决策单调推进——同一消息一旦被 Snip，不会回退为完整。
 *
 * <h3>三级递进</h3>
 * <ol>
 *   <li>Snip (Tier 1)：水位 ≥ snipThreshold，截短旧 tool result 内容</li>
 *   <li>Prune (Tier 2)：水位 ≥ pruneThreshold，替换为占位符（隐含 Snip）</li>
 *   <li>Summarize (Tier 3)：水位 ≥ summarizeThreshold，调用 LLM 生成摘要，删除旧消息
 *       ——删除旧消息会导致 tool_use/tool_result 配对断裂，由 ContextCompactor 调用 PairingRepairer 修复</li>
 * </ol>
 *
 * <p>Summarize 在 Snip/Prune 之后执行，形成递进压缩。LLM 摘要通过 LlmClient.chat() 调用，
 * 不注入工具 Schema（纯文本生成），摘要结果写入 CompressionState.lastSummary。</p>
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
     * 根据水位执行压缩。
     * 返回压缩后的消息列表（修改后的副本）。
     *
     * 三级递进：先 Snip → 再 Prune → 最后 Summarize。
     * 每一级包含前一级（Prune 隐含 Snip，Summarize 在 Prune 之后执行）。
     */
    public List<ChatMessage> compress(List<ChatMessage> messages,
                                      CompressionState state,
                                      double waterLevel,
                                      int tailGuardTurns) {
        if (waterLevel >= summarizeThreshold) {
            log.info("Water level {} >= {}, applying Snip + Prune + Summarize (Tier 1+2+3)", waterLevel, summarizeThreshold);
            // 先执行低级压缩（Snip + Prune），减少摘要输入长度
            applyPrune(messages, state, tailGuardTurns);
            // 再执行 Summarize（删除旧消息，替换为摘要）
            applySummarize(messages, state, tailGuardTurns);
        } else if (waterLevel >= pruneThreshold) {
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
     * Summarize（Tier 3）：LLM 摘要，删除旧消息。
     *
     * <p>执行流程：
     * <ol>
     *   <li>确定摘要范围：tailStart 之前的所有消息（与 Snip/Prune 的保护区逻辑一致）</li>
     *   <li>拼接旧消息文本（截断到 summarizeMaxInputChars），构造摘要请求 prompt</li>
     *   <li>调用 LlmClient.chat() 生成摘要（不注入工具 Schema，纯文本生成）</li>
     *   <li>将摘要写入 CompressionState.lastSummary</li>
     *   <li>从消息列表中删除已被摘要覆盖的旧消息（保留 summarizedUpToIndex 之后的消息）</li>
     *   <li>更新 CompressionState.summarizedUpToIndex</li>
     * </ol>
     * </p>
     *
     * <p>删除旧消息会导致 tool_use/tool_result 配对断裂，
     * 由 ContextCompactor 在调用 compress() 之后调用 PairingRepairer.repair() 修复。
     * 该调用链已存在于 ContextCompactor.beforeModelCall 中。</p>
     *
     * <p>压缩决策单调推进：已被 Summarize 的旧消息被删除，不会在后续轮次中恢复。
     * 摘要本身不可被再压缩（作为 Summary 层注入，位于 System Prompt 之后、Transcript 之前）。</p>
     */
    private void applySummarize(List<ChatMessage> messages, CompressionState state, int tailGuardTurns) {
        int tailStart = Math.max(0, messages.size() - tailGuardTurns * 2 - 2);

        if (tailStart <= 0) {
            log.debug("Summarize skipped: no messages outside tail guard");
            return;
        }

        // 如果已经摘要过且没有新消息需要摘要，跳过
        if (state.getSummarizedUpToIndex() >= tailStart) {
            log.debug("Summarize skipped: already summarized up to index {}", state.getSummarizedUpToIndex());
            return;
        }

        // 1. 拼接旧消息文本
        StringBuilder dialogText = new StringBuilder();
        for (int i = 0; i < tailStart; i++) {
            String line = formatMessageForSummary(messages.get(i));
            if (line != null && !line.isBlank()) {
                dialogText.append(line).append("\n");
                // 截断到最大输入长度
                if (dialogText.length() >= summarizeMaxInputChars) {
                    dialogText.append("... [truncated]\n");
                    break;
                }
            }
        }

        if (dialogText.isEmpty()) {
            log.debug("Summarize skipped: no dialog text to summarize");
            return;
        }

        // 2. 构造摘要请求 prompt
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

        // 3. 调用 LLM 生成摘要（不注入工具 Schema，纯文本生成）
        List<ChatMessage> summaryMessages = new ArrayList<>();
        summaryMessages.add(SystemMessage.from("你是一个对话摘要生成器。请严格按指令生成摘要。"));
        summaryMessages.add(UserMessage.from(summaryPrompt));

        try {
            LlmResponse response = llmClient.chat(summaryMessages, null);
            String summary = response.aiMessage().text();

            if (summary == null || summary.isBlank()) {
                log.warn("Summarize: LLM returned empty summary, skipping");
                return;
            }

            // 截断到配置的最大输出长度
            if (summary.length() > summarizeMaxOutputChars) {
                summary = summary.substring(0, summarizeMaxOutputChars) + "...";
            }

            // 4. 写入 CompressionState.lastSummary
            // 如果已有旧摘要，追加合并
            String existingSummary = state.getLastSummary();
            if (existingSummary != null && !existingSummary.isBlank()) {
                summary = existingSummary + "\n\n" + summary;
            }
            state.setLastSummary(summary);

            // 5. 删除已被摘要覆盖的旧消息（保留 tailStart 之后的消息）
            // 需要删除 messages[0..tailStart-1]
            int removeCount = tailStart;
            // 使用 subList 删除前 removeCount 条
            messages.subList(0, removeCount).clear();

            // 6. 更新 CompressionState.summarizedUpToIndex
            // 删除后消息列表缩小，summarizedUpToIndex 表示"已摘要覆盖到原始列表的哪个位置"
            state.setSummarizedUpToIndex(tailStart);

            log.info("Summarize applied: removed {} old messages, summary length={} chars",
                    removeCount, summary.length());
            log.debug("Summary preview: {}", summary.length() > 200 ? summary.substring(0, 200) + "..." : summary);

        } catch (Exception e) {
            log.error("Summarize failed: {}", e.getMessage(), e);
            // Summarize 失败不中断主流程，Snip/Prune 已经执行过，上下文仍可用
        }
    }

    /**
     * 将消息格式化为摘要输入文本。
     */
    private String formatMessageForSummary(ChatMessage msg) {
        if (msg instanceof SystemMessage sm) {
            return null; // System Message 不纳入摘要
        }
        if (msg instanceof UserMessage um) {
            return "[用户] " + um.singleText();
        }
        if (msg instanceof AiMessage am) {
            String text = am.text();
            if (text != null && !text.isBlank()) {
                return "[助手] " + text;
            }
            // 只有工具调用没有文本的 AiMessage 不纳入摘要
            return null;
        }
        if (msg instanceof ToolExecutionResultMessage trm) {
            String content = trm.text();
            if (content != null && !content.isBlank()) {
                // 截短过长的工具结果
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "...";
                }
                return "[工具结果:" + trm.toolName() + "] " + content;
            }
            return null;
        }
        return null;
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
