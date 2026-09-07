package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.LlmSupport;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.config.AgentConfig;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 摘要生成器。对保护区之外的块生成结构化摘要，供 SUMMARIZE 档在删除原文前保住信息。
 * 内容超长时分段摘要，不硬截断。
 */
public class ContextSummarizer {
    private static final Logger log = LoggerFactory.getLogger(ContextSummarizer.class);

    private final LlmSupport llmSupport;
    private final String transcriptPath;
    private final int maxInputChars;
    private final int maxOutputChars;

    public ContextSummarizer(LlmSupport llmSupport, String transcriptPath,
                             AgentConfig.ContextConfig config) {
        this.llmSupport = llmSupport;
        this.transcriptPath = transcriptPath != null ? transcriptPath : "(transcript 路径不可用)";
        this.maxInputChars = config.getSummarizeMaxInputChars();
        this.maxOutputChars = config.getSummarizeMaxOutputChars();
    }

    /**
     * 生成可注入上下文的摘要文本。
     */
    public String summarize(ContextWindow window, int protectedFrom, String existingSummary) {
        List<ContextBlock> removable = collectRemovable(window, protectedFrom);
        if (removable.isEmpty()) {
            return null;
        }

        List<String> segments = segment(removable);
        String summary = existingSummary;

        for (int i = 0; i < segments.size(); i++) {
            String merged = generateSummary(segments.get(i), summary, i + 1, segments.size());
            if (merged == null || merged.isBlank()) {
                log.warn("[Summary] 第 {}/{} 段摘要未生成，保留已有摘要并记录降级说明", i + 1, segments.size());
                return fallback(summary);
            }
            summary = merged;
        }
        return summary;
    }

    /** 摘要生成失败时的兜底文案。 */
    private String fallback(String existingSummary) {
        if (existingSummary != null && !existingSummary.isBlank()) return existingSummary;
        return "(摘要生成失败，历史对话已裁剪。完整记录: " + transcriptPath + ")";
    }

    /**
     * 收集保护区之前的全部块。筛选条件必须与 removeBefore 一致。
     */
    private List<ContextBlock> collectRemovable(ContextWindow window, int protectedFrom) {
        List<ContextBlock> removable = new ArrayList<>();
        List<ContextBlock> blocks = window.blocks();
        int limit = Math.min(protectedFrom, blocks.size());
        for (int i = 0; i < limit; i++) {
            removable.add(blocks.get(i));
        }
        return removable;
    }

    // ═══════════════════ 分段 ═══════════════════

    /** 按段预算把待摘要块切成若干段。 */
    private List<String> segment(List<ContextBlock> blocks) {
        List<String> segments = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (ContextBlock block : blocks) {
            String line = block.text();
            if (line.isBlank()) continue;

            if (line.length() > maxInputChars) {
                if (!buf.isEmpty()) {
                    segments.add(buf.toString());
                    buf.setLength(0);
                }
                for (int i = 0; i < line.length(); i += maxInputChars) {
                    segments.add(line.substring(i, Math.min(line.length(), i + maxInputChars)));
                }
                continue;
            }
            if (buf.length() + line.length() + 1 > maxInputChars) {
                segments.add(buf.toString());
                buf.setLength(0);
            }
            buf.append(line).append('\n');
        }
        if (!buf.isEmpty()) segments.add(buf.toString());
        return segments;
    }


    // ═══════════════════ LLM 调用 ═══════════════════

    /**
     * 调用 LLM 把一段对话增量合并进已有摘要。
     */
    private String generateSummary(String segmentText, String existingSummary, int index, int total) {
        String existingSection = (existingSummary != null && !existingSummary.isBlank())
                ? existingSummary : "(无旧摘要，首次生成)";
        String segmentHint = total > 1
                ? "\n注意：本片段是本次裁剪内容的第 " + index + "/" + total + " 段，后面还有内容。"
                + "只做合并式摘要，不要对整体进展下结论。\n"
                : "";

        String prompt = """
                请将对话片段压缩为结构化摘要，严格按以下 4 段格式输出：

                1. User Requests — 用户消息原文（逐条照抄，不得改写、不得省略、不得合并）
                   - [已完成] <用户消息原文>
                   - [进行中] <用户消息原文>
                2. Key Context and Decisions — 关键上下文、已做的决策、已获取的关键信息
                3. User Preferences and Updates — 本轮中发现/更新/确认的用户偏好和记忆
                4. Pending Tasks and Current Work — 未完成任务与当前进展

                关于第 1 段：
                - 用户消息原文会从上下文删除，本段是后续了解用户诉求的唯一依据。
                - 逐条照抄用户消息全文，不得概括、不得压缩、不得合并相似诉求。
                - 每条按其进展标注 [已完成] 或 [进行中]：已产出结果的标 [已完成]，
                  已开始但仍在处理或等待后续步骤的标 [进行中]。

                合并规则：
                - 给了旧摘要就必须合并为一份新摘要：同一件事只留一条，不许并列、不许拼接两段摘要。
                - 旧摘要里已存在的用户消息原文与进展标注要保留，不得删除或改写。

                输出要求：不超过 %d 字符；第 2~4 段用陈述句，保留关键事实、文件路径与数字，
                省略过程性描述；不要输出 <summary> 标签，不要输出任何格式之外的说明。%s
                完整对话记录路径: %s

                === 旧摘要（如有） ===
                %s

                === 待摘要的对话片段 ===
                %s
                """.formatted(maxOutputChars, segmentHint, transcriptPath, existingSection, segmentText);

        List<ChatMessage> messages = List.of(
                SystemMessage.from("你是一个对话摘要生成器。请严格按指令生成摘要。"),
                UserMessage.from(prompt));

        String summary;
        try {
            summary = llmSupport.complete(messages);
        } catch (Exception e) {
            log.error("[Summary] Summarize 失败: {}", e.getMessage());
            return null;
        }
        if (summary == null || summary.isBlank()) {
            return null;
        }
        return summary;
    }
}
