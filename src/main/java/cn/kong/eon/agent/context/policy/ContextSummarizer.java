package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.LlmSupport;
import cn.kong.eon.agent.context.block.CompressionLevel;
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

        String dialogText = formatBlocks(removable);
        if (dialogText.isBlank()) return null;

        String summary;
        try {
            summary = generateSummary(dialogText, existingSummary);
        } catch (Exception e) {
            log.error("[Summary] Summarize 失败: {}", e.getMessage());
            summary = null;
        }

        if (summary == null || summary.isBlank()) {
            log.warn("[Summary] 摘要未生成，保留旧摘要并记录降级说明");
            return fallback(existingSummary);
        }
        return "<summary>\n" + summary + "\n</summary>";
    }

    /** 摘要生成失败时的兜底文案：有旧摘要就沿用，没有则记一条指向完整记录的降级说明。 */
    private String fallback(String existingSummary) {
        if (existingSummary != null && !existingSummary.isBlank()) return existingSummary;
        return "<summary>\n(摘要生成失败，历史对话已裁剪。完整记录: " + transcriptPath + ")\n</summary>";
    }

    /**
     * 收集保护区之前的全部块。
     * <p>
     * 历史用户输入同样进入摘要——SUMMARIZE 不是就地改写而是内容转移，先由摘要吸收信息
     * 再释放原文，诉求改由摘要第 1 段承载。被 pin 的当前用户输入恒在保护区内，不在收集范围。
     * <p>
     * 本方法的筛选条件必须与 {@link ContextWindow#removeBefore(int)} 完全一致，
     * 否则会出现"摘要了没删"（重复摘要）或"删了没摘要"（信息丢失）。
     */
    private List<ContextBlock> collectRemovable(ContextWindow window, int protectedFrom) {
        List<ContextBlock> removable = new ArrayList<>();
        List<ContextBlock> blocks = window.blocks();
        int limit = Math.min(protectedFrom, blocks.size());
        for (int i = 0; i < limit; i++) {
            ContextBlock block = blocks.get(i);
            if (block.isPinned()) continue;
            removable.add(block);
        }
        return removable;
    }

    /** 调用 LLM 生成增量摘要。 */
    private String generateSummary(String dialogText, String existingSummary) {
        String existingSection = (existingSummary != null && !existingSummary.isBlank())
                ? existingSummary : "(无旧摘要，首次生成)";

        String prompt = """
                请将以下历史对话压缩为结构化摘要，严格按以下 4 段格式输出：

                1. User Requests — 诉求清单（按时间顺序，每条一行）
                   - [已完成] <诉求摘要>
                   - [进行中] <诉求摘要>
                2. Key Context and Decisions — 关键上下文、已做的决策、已获取的关键信息
                3. User Preferences and Updates — 本轮中发现/更新/确认的用户偏好和记忆
                4. Pending Tasks and Current Work — 未完成任务与当前进展

                关于第 1 段：
                - 历史诉求的原文已被永久删除，本摘要是后续了解用户诉求的唯一依据，不得省略任何一条。
                - 用户可能有多个诉求且各自处于不同阶段，逐条列出并标注状态。

                摘要要求：不超过 %d 字符；陈述句；保留关键事实与数字；省略过程性描述。
                如有旧摘要，请合并旧摘要与新对话生成新版摘要（增量），不要直接拼接。

                完整对话记录路径: %s

                === 旧摘要（如有） ===
                %s

                === 本次被裁剪的对话 ===
                %s
                """.formatted(maxOutputChars, transcriptPath, existingSection, dialogText);

        List<ChatMessage> messages = List.of(
                SystemMessage.from("你是一个对话摘要生成器。请严格按指令生成摘要。"),
                UserMessage.from(prompt));

        String summary = llmSupport.complete(messages);
        if (summary == null || summary.isBlank()) {
            return null;
        }
        return summary.length() > maxOutputChars
                ? summary.substring(0, maxOutputChars) + "..."
                : summary;
    }

    /** 把待摘要的块格式化为对话文本，按块逐条控制长度。 */
    private String formatBlocks(List<ContextBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContextBlock block : blocks) {
            String line = formatBlock(block);
            if (line.isBlank()) continue;
            sb.append(line).append('\n');
            if (sb.length() >= maxInputChars) {
                sb.append("... [已截断]\n");
                break;
            }
        }
        return sb.toString();
    }

    private String formatBlock(ContextBlock block) {
        return switch (block.kind()) {
            case USER_INPUT -> "[用户] " + truncate(block.text(), 2000);
            case AI_TEXT -> "[助手] " + truncate(block.text(), 1000);
            case TOOL_ARGS -> "[助手调用] " + block.toolName() + "(" + truncate(block.text(), 200) + ")";
            case TOOL_RESULT -> "[工具结果:" + block.toolName() + "] " + resultLine(block);
            case OTHER -> "[其他] " + truncate(block.text(), 1000);
        };
    }

    /**
     * 工具结果行。已被清空的块只有占位文本，此时输出指向磁盘副本的线索行，
     * 让摘要至少知道这里发生过什么、去哪里取回。
     */
    private String resultLine(ContextBlock block) {
        if (block.disposedAtOrAbove(CompressionLevel.PRUNE)) {
            return block.refId() != null
                    ? "(内容已清除，可用 read_file 读取 artifact 引用 " + block.refId() + ")"
                    : "(内容已清除，无磁盘副本)";
        }
        return truncate(block.text(), 500);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
