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
                    把本片段里出现的问答追加进第 1 段，第 2~4 段先按本片段目前的最新一次问答写；
                    不要对整体进展下结论，也不要提前总结还没出现的部分。
                    """.formatted(index, total);
        }

        String prompt = """
                请将待摘要的对话片段整理为结构化摘要，严格按以下 4 段格式输出：

                1. User Requests
                   - [已完成] <user_input>用户消息原文</user_input>
                     - 结论: <一句话结论，只写结果不写过程>
                     - 关键事实: <优先记产出物：新建或修改的文件路径、生成的文档、下载的产物；
                       以及已定决策、用户偏好、数字/ID/日期。没有则写"无">
                   - [进行中] <user_input>用户消息原文</user_input>
                     - 结论: ...
                     - 关键事实: ...
                2. Key Context and Decisions
                3. User Preferences and Updates
                4. Pending Tasks and Current Work

                关于第 1 段
                - 每轮问答占一个条目，**历史问答与最新一次问答一视同仁**，都要逐条列出。
                - 用户消息原文会从上下文删除，这里是后续了解用户诉求的唯一依据：逐条照抄全文，
                  不得概括、压缩或合并。片段中以 [USER] 开头的内容即为用户消息，去掉 [USER] 前缀即为原文，
                  用 <user_input> 标签包裹。已完结的标 [已完成]，尚未做完的标 [进行中]。
                - 每次提问至少留一个条目，绝不因为两次提问看起来相似就合并成一条。
                - 旧摘要里已存在的条目**原样保留、只增不删**，不得删改、合并或重写。
                - 关键事实优先记产出物与文件路径——这些内容一旦丢失就无法从对话里再找回。

                关于第 2~4 段
                - **只覆盖最新一次问答**（片段中最后那次用户提问），不要把历史问答的决策和待办堆进来。
                - 用陈述句，保留关键事实、文件路径与数字，省略过程性描述。
                - 若旧摘要的第 2~4 段描述的是更早的提问，而本片段出现了更新的提问：
                  把旧的第 2~4 段内容压缩成结论与关键事实，随那次提问并入第 1 段作为历史条目，
                  第 2~4 段改写为本片段最后那次问答的情况。

                对话片段格式说明：
                - [USER] 开头的为用户消息
                - [AI] 开头的为助手回复
                - [TOOL:工具名] 开头的为工具调用参数
                - [RESULT:工具名] 开头的为工具执行结果

                容量不够时
                - 逼近字符上限时，从**最早**的历史条目开始降级：保留 <user_input> 原文，只留诉求要点一句话 + 关键事实。
                  最新一次问答与第 2~4 段始终完整。

                其他：
                - 片段不构成一次完整问答（如只剩工具调用）时，把信息并入第 2 段 Key Context and Decisions，
                  不要单建条目。

                输出要求：不超过 %d 字符；只输出上述结构，不要任何额外说明。
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
