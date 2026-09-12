package cn.kong.eon.context.summary;

import cn.kong.eon.context.ContextWindow;
import cn.kong.eon.context.block.BlockKind;
import cn.kong.eon.context.block.ContextBlock;
import cn.kong.eon.llm.LlmService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.apache.commons.lang3.StringUtils;
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

    private final LlmService llmService;
    private final int maxInputChars;
    private final int maxOutputChars;

    /** 应用级单例，会话相关信息由参数传入。 */
    public ContextSummarizer(LlmService llmService, int maxInputChars, int maxOutputChars) {
        this.llmService = llmService;
        this.maxInputChars = maxInputChars;
        this.maxOutputChars = maxOutputChars;
    }

    /**
     * 生成摘要。返回 null 表示保护区之前无可摘要内容。
     * 分段摘要中某段失败时跳过，全部失败才用兜底提示。
     */
    public String summarize(ContextWindow window, int protectedFrom,
                            String existingSummary, String ledgerPath) {
        List<ContextBlock> removable = collectRemovable(window, protectedFrom);
        if (removable.isEmpty()) {
            return null;
        }

        List<String> segments = segment(removable);
        String summary = existingSummary;
        boolean anySuccess = false;

        for (int i = 0; i < segments.size(); i++) {
            String merged = generateSummary(segments.get(i), summary, i + 1, segments.size(), ledgerPath);
            if (merged == null || merged.isBlank()) {
                log.warn("[Summary] 第 {}/{} 段摘要失败，跳过", i + 1, segments.size());
                continue;
            }
            summary = merged;
            anySuccess = true;
        }

        if (!anySuccess) {
            log.warn("[Summary] 全部 {} 段摘要均失败，使用兜底摘要", segments.size());
            return fallback(summary, ledgerPath);
        }
        return summary;
    }

    /** 全部段落失败时的兜底提示。 */
    private static String fallback(String existingSummary, String ledgerPath) {
        if (existingSummary != null && !existingSummary.isBlank()) return existingSummary;
        return "[摘要失败] 历史对话摘要生成失败，完整对话记录: "
                + (ledgerPath != null ? ledgerPath : "(账本路径不可用)");
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

    /**
     * 按段预算把待摘要块切成若干段。
     * 每个 block 按类型添加角色标记前缀，让 LLM 能区分对话角色。
     * 超长 block 独占一段；普通 block 累积到预算上限后切段。
     */
    private List<String> segment(List<ContextBlock> blocks) {
        List<String> segments = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (ContextBlock block : blocks) {
            String line = block.text();
            if (line.isBlank()) continue;

            String taggedLine = tagBlock(block.kind(), block.toolName(), line);

            if (taggedLine.length() > maxInputChars) {
                if (!buf.isEmpty()) {
                    segments.add(buf.toString());
                    buf.setLength(0);
                }
                // 超长行独占一段（通常是工具结果）
                segments.add(taggedLine);
                continue;
            }
            if (buf.length() + taggedLine.length() + 1 > maxInputChars) {
                segments.add(buf.toString());
                buf.setLength(0);
            }
            buf.append(taggedLine).append('\n');
        }
        if (!buf.isEmpty()) segments.add(buf.toString());
        return segments;
    }

    /**
     * 为 block 内容添加角色标记前缀，帮助 LLM 区分对话角色。
     * 格式：[USER] 用户消息原文 / [AI] 助手回复 / [TOOL:工具名] 工具调用参数 / [RESULT:工具名] 工具执行结果
     */
    private static String tagBlock(BlockKind kind, String toolName, String text) {
        return switch (kind) {
            case USER_INPUT -> "[USER] " + text;
            case AI_TEXT -> "[AI] " + text;
            case TOOL_ARGS -> "[TOOL:" + toolName + "] " + text;
            case TOOL_RESULT -> "[RESULT:" + toolName + "] " + text;
        };
    }


    // ═══════════════════ LLM 调用 ═══════════════════

    /**
     * 调用 LLM 把一段对话增量合并进已有摘要。
     */
    private String generateSummary(String segmentText, String existingSummary,
                                   int index, int total, String ledgerPath) {
        String existingSection = StringUtils.isNotBlank(existingSummary) ? existingSummary : "(无旧摘要，首次生成)";
        String segmentHint = "";
        if (total > 1) {
            segmentHint = """
                    注意：本片段是本次裁剪内容的第 %d/%d 段，后面还有内容。
                    只做合并式摘要，不要对整体进展下结论。
                    """.formatted(index, total);
        }

        String prompt = """
                请将对话片段压缩为结构化摘要，严格按以下 4 段格式输出：

                1. User Requests
                   - [已完成] <user_input>用户消息原文</user_input>
                   - [进行中] <user_input>用户消息原文</user_input>
                2. Key Context and Decisions
                3. User Preferences and Updates
                4. Pending Tasks and Current Work

                关于第 1 段：
                - 用户消息原文会从上下文删除，本段是后续了解用户诉求的唯一依据。
                - 逐条照抄用户消息全文，不得概括、不得压缩、不得合并相似诉求。
                - 对话片段中以 [USER] 开头的内容即为用户消息，提取时去掉 [USER] 前缀后即为消息原文。
                - 用 <user_input> 标签包裹每条用户消息原文。

                关于第 2~4 段：用陈述句，保留关键事实、文件路径与数字，省略过程性描述。

                对话片段格式说明：
                - [USER] 开头的为用户消息
                - [AI] 开头的为助手回复
                - [TOOL:工具名] 开头的为工具调用参数
                - [RESULT:工具名] 开头的为工具执行结果

                合并规则：
                - 给了旧摘要就必须合并为一份新摘要：同一件事只留一条，不许并列、不许拼接两段摘要。
                - 旧摘要里已存在的用户消息原文与进展标注要保留，不得删除或改写。

                输出要求：不超过 %d 字符；不要输出任何格式之外的说明。
                %s
                完整对话记录路径: %s

                === 旧摘要 ===
                %s

                === 待摘要的对话片段 ===
                %s
                """.formatted(maxOutputChars, segmentHint,
                        ledgerPath != null ? ledgerPath : "(账本路径不可用)",
                        existingSection, segmentText);

        List<ChatMessage> messages = List.of(
                SystemMessage.from("你是一个对话摘要生成器。请严格按指令生成摘要。"),
                UserMessage.from(prompt));

        try {
            String summary = llmService.complete(messages);
            return (summary != null && !summary.isBlank()) ? summary : null;
        } catch (Exception e) {
            log.error("[Summary] LLM 不可用（{}），第 {}/{} 段跳过: {}",
                    e.getMessage(), index, total, e.getMessage());
            return null;
        }
    }
}
