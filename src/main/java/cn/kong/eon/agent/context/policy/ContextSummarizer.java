package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextTags;
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
 * 摘要生成器。对尾部保护区之外的块生成结构化摘要，供 SUMMARIZE 档在删除原文前保住信息。
 * <p>
 * 只负责生成文本，不改动窗口也不改动压缩状态——"先摘要后删除"的顺序由
 * {@link CompressionPolicy} 编排，保证原文在摘要生成成功之后才被移除。
 * <p>
 * <b>内容超长时分段摘要，不硬截断</b>：待摘要内容按 {@code summarizeMaxInputChars} 切段，
 * 逐段调用 LLM 并与已有摘要增量合并（第 N 段的输出作为第 N+1 段的旧摘要）。
 * 硬截断会静默丢掉尾部信息，而丢掉的往往是最近、最相关的部分。
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
     * 生成可注入上下文的摘要文本（含 {@code <summary>} 标签）。
     *
     * @param window          上下文窗口，只读
     * @param protectedFrom   保护区起始下标，只摘要此下标之前的块
     * @param existingSummary 上一轮的摘要，用于增量合并；无则为 null
     * @return 摘要文本；保护区之前无可摘要内容时返回 null
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

    /** 摘要生成失败时的兜底文案：有旧摘要就沿用，没有则记一条指向完整记录的降级说明。 */
    private String fallback(String existingSummary) {
        if (existingSummary != null && !existingSummary.isBlank()) return existingSummary;
        return "(摘要生成失败，历史对话已裁剪。完整记录: " + transcriptPath + ")";
    }

    /**
     * 收集保护区之前的全部块。
     * <p>
     * 历史用户输入同样进入摘要——SUMMARIZE 不是就地改写而是内容转移，先由摘要吸收信息
     * 再释放原文。删除后上下文里第一条不再是用户手动输入的原文，用户诉求改由
     * 摘要第 1 段<b>逐条原样</b>承载。
     * <p>
     * 本方法的筛选条件必须与 {@link ContextWindow#removeBefore(int)} 完全一致，
     * 否则会出现"摘要了没删"（重复摘要）或"删了没摘要"（信息丢失）。
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

    /**
     * 按段预算把待摘要块切成若干段。整块能放下就整块打包，
     * 单个块自己就超预算时按字符切开——宁可多调用一次 LLM，也不静默丢尾部内容。
     */
    private List<String> segment(List<ContextBlock> blocks) {
        List<String> segments = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (ContextBlock block : blocks) {
            String line = formatBlock(block);
            if (line.isBlank()) continue;

            if (line.length() > maxInputChars) {
                if (buf.length() > 0) {
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
        if (buf.length() > 0) segments.add(buf.toString());
        return segments;
    }

    /**
     * 块 → 一行对话文本：直接复用渲染层的输出，摘要看到的形态与模型实际看到的完全一致。
     * <p>
     * 这里不再二次截断：进到 SUMMARIZE 的块已经过 SNIP/PRUNE 的就地处置，
     * 剩下的长度就是它该有的长度；再砍一刀只会砍掉摘要唯一能依据的内容。
     * 长度问题交给 {@link #segment} 按段切分解决。
     */
    private String formatBlock(ContextBlock block) {
        return ContextTags.render(block);
    }

    // ═══════════════════ LLM 调用 ═══════════════════

    /**
     * 调用 LLM 把一段对话增量合并进已有摘要。
     *
     * @param segmentText     本段对话
     * @param existingSummary 已有摘要（本轮前几段的产出与上轮摘要合并后的结果），无则为 null
     * @param index           本段序号，从 1 起
     * @param total           总段数
     * @return 合并后的摘要正文（不含 {@code <summary>} 标签）；失败返回 null
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
        return summary.length() > maxOutputChars
                ? summary.substring(0, maxOutputChars) + "..."
                : summary;
    }
}
