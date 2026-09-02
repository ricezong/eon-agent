package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.LlmSupport;
import cn.kong.eon.agent.context.block.CompressionLevel;
import cn.kong.eon.agent.context.block.ContextBlock;
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
                             int maxInputChars, int maxOutputChars) {
        this.llmSupport = llmSupport;
        this.transcriptPath = transcriptPath != null ? transcriptPath : "(transcript 路径不可用)";
        this.maxInputChars = maxInputChars;
        this.maxOutputChars = maxOutputChars;
    }

    /**
     * 生成可注入上下文的摘要文本（含 {@code <summary>} 标签）。
     *
     * @param window          上下文窗口，只读
     * @param cutoffTurn      尾部保护区起始轮次，只摘要此轮次之前的块
     * @param existingSummary 上一轮的摘要，用于增量合并；无则为 null
     * @return 摘要文本；保护区外无可摘要内容时返回 null
     */
    public String summarize(ContextWindow window, int cutoffTurn, String existingSummary) {
        List<ContextBlock> removable = collectRemovable(window, cutoffTurn);
        if (removable.isEmpty()) {
            log.info("[压缩] Summarize 跳过：尾部保护区外无可摘要内容");
            return null;
        }

        String dialogText = formatBlocks(removable);
        if (dialogText.isBlank()) return null;

        String summary;
        try {
            summary = generateSummary(dialogText, existingSummary);
        } catch (Exception e) {
            log.error("[压缩] Summarize 失败: {}", e.getMessage());
            summary = null;
        }

        if (summary == null || summary.isBlank()) {
            log.warn("[压缩] 摘要未生成，保留旧摘要并记录降级说明");
            return fallback(existingSummary);
        }
        return "<summary>\n" + summary + "\n</summary>";
    }

    /** 摘要生成失败时的兜底文案：有旧摘要就沿用，没有则记一条指向完整记录的降级说明。 */
    private String fallback(String existingSummary) {
        if (existingSummary != null && !existingSummary.isBlank()) return existingSummary;
        return "<summary>\n(摘要生成失败，历史对话已裁剪。完整记录: " + transcriptPath + ")\n</summary>";
    }

    /** 收集保护区之外、且允许改写的块。 */
    private List<ContextBlock> collectRemovable(ContextWindow window, int cutoffTurn) {
        List<ContextBlock> removable = new ArrayList<>();
        for (ContextBlock block : window.blocks()) {
            if (block.turn() < cutoffTurn && block.retention().compressible()) {
                removable.add(block);
            }
        }
        return removable;
    }

    /** 调用 LLM 生成增量摘要。 */
    private String generateSummary(String dialogText, String existingSummary) {
        String existingSection = (existingSummary != null && !existingSummary.isBlank())
                ? existingSummary : "(无旧摘要，首次生成)";

        String prompt = """
                请将以下历史对话压缩为结构化摘要，严格按以下 4 段格式输出：

                1. Primary Request and Intent — 用户的核心诉求（逐条列出）
                2. Key Context and Decisions — 关键上下文、已做的决策、已获取的关键信息
                3. User Preferences and Updates — 本轮中发现/更新/确认的用户偏好和记忆
                4. Pending Tasks and Current Work — 未完成任务与当前进展

                注意：用户原始消息已由系统逐字保留在上下文中，第 1 段只需概括意图，
                不要逐字复述用户原话。

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
            log.warn("[压缩] LLM 返回空摘要");
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
            if (line == null || line.isBlank()) continue;
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
            default -> null;
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
