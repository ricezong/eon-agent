package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.BlockProjector;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.agent.context.block.Retention;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.model.CompressionState;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Summarize：LLM 生成结构化摘要，并删除被覆盖的旧块（有损，最后一级）。
 * <p>
 * <b>关键改进</b>：删除走 {@link ContextWindow#removeBefore(int)}，
 * 它只删 {@link Retention#COMPRESSIBLE} 的块，
 * {@link Retention#VERBATIM} 的用户消息与系统块<b>自动保留</b>。
 * <p>
 * 过去是 {@code subList(0, n).clear()}，会把早期用户消息连锅端掉，
 * 只能寄望 LLM 在摘要 prompt 里自觉执行"逐字保留用户消息"这一条——
 * 把安全约束交给模型自觉，本身就是设计缺陷。
 */
public class SummarizeRule implements ContextRule {
    private static final Logger log = LoggerFactory.getLogger(SummarizeRule.class);

    private final double waterThreshold;
    private final int summarizeTurns;
    private final int maxInputChars;
    private final int maxOutputChars;
    private final LlmClient llmClient;
    private final String transcriptPath;

    public SummarizeRule(double waterThreshold, int summarizeTurns,
                         int maxInputChars, int maxOutputChars,
                         LlmClient llmClient, String transcriptPath) {
        this.waterThreshold = waterThreshold;
        this.summarizeTurns = summarizeTurns;
        this.maxInputChars = maxInputChars;
        this.maxOutputChars = maxOutputChars;
        this.llmClient = llmClient;
        this.transcriptPath = transcriptPath != null ? transcriptPath : "(transcript 路径不可用)";
    }

    @Override
    public String name() {
        return "Summarize";
    }

    @Override
    public int level() {
        return LEVEL_SUMMARIZE;
    }

    @Override
    public List<Trigger> triggers() {
        return List.of(
                new Trigger.WaterLevel(waterThreshold),
                new Trigger.TurnInterval(summarizeTurns, 3));
    }

    @Override
    public RuleOutcome apply(RuleContext ctx) {
        ContextWindow window = ctx.window();
        int cutoffTurn = ctx.cutoffTurn();

        List<ContextBlock> removable = new ArrayList<>();
        for (ContextBlock block : window.blocks()) {
            if (block.turn() < cutoffTurn && block.retention().compressible()) {
                removable.add(block);
            }
        }
        if (removable.isEmpty()) {
            log.debug("[压缩] Summarize 跳过：尾部保护区外无可摘要内容");
            return RuleOutcome.none();
        }

        String dialogText = formatBlocks(removable);
        if (dialogText.isBlank()) {
            return RuleOutcome.none();
        }

        CompressionState state = ctx.compressionState();
        long before = window.totalChars();

        String summary;
        try {
            summary = generateSummary(dialogText, state);
        } catch (Exception e) {
            log.error("[压缩] Summarize 失败: {} → 降级为直接删除旧块", e.getMessage());
            summary = null;
        }

        if (summary != null) {
            state.setLastSummary(summary);
        } else if (state.getLastSummary() == null) {
            state.setLastSummary("(摘要生成失败，历史对话已裁剪。完整记录: " + transcriptPath + ")");
        }

        List<ContextBlock> removed = window.removeBefore(cutoffTurn);
        state.setSummarizedMessageCount(state.getSummarizedMessageCount() + removed.size());

        long after = window.totalChars();
        log.info("[压缩] Summarize: 删除 {} 个块（保留 {} 个逐字块）| {} -> {} 字符",
                removed.size(), removable.size() - removed.size(), before, after);
        return RuleOutcome.of(removed.size(), before, after, "Summarize×" + removed.size());
    }

    private String generateSummary(String dialogText, CompressionState state) {
        String existing = state.getLastSummary();
        String existingSection = (existing != null && !existing.isBlank()) ? existing : "(无旧摘要，首次生成)";

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
                （当任务或状态不清楚时，可用搜索工具检索该文件而不是猜测。）

                === 旧摘要（如有） ===
                %s

                === 本次被裁剪的对话 ===
                %s
                """.formatted(maxOutputChars, transcriptPath, existingSection, dialogText);

        List<ChatMessage> messages = List.of(
                SystemMessage.from("你是一个对话摘要生成器。请严格按指令生成摘要。"),
                UserMessage.from(prompt));

        LlmResponse response = llmClient.chat(messages, null);
        String summary = response.aiMessage() != null ? response.aiMessage().text() : null;
        if (summary == null || summary.isBlank()) {
            log.warn("[压缩] Summarize: LLM 返回空摘要");
            return null;
        }
        return summary.length() > maxOutputChars
                ? summary.substring(0, maxOutputChars) + "..."
                : summary;
    }

    /**
     * 把待删除的块格式化为对话文本。按块而非按消息遍历，
     * 因此工具参数块与正文块可以分别控制长度。
     */
    private String formatBlocks(List<ContextBlock> blocks) {
        List<ChatMessage> messages = BlockProjector.assemble(blocks);
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            String line = formatMessage(msg);
            if (line != null && !line.isBlank()) {
                sb.append(line).append('\n');
                if (sb.length() >= maxInputChars) {
                    sb.append("... [已截断]\n");
                    break;
                }
            }
        }
        return sb.toString();
    }

    private String formatMessage(ChatMessage msg) {
        if (msg instanceof UserMessage um) {
            return "[用户] " + truncate(um.singleText(), 2000);
        }
        if (msg instanceof AiMessage am) {
            if (am.text() != null && !am.text().isBlank()) {
                return "[助手] " + truncate(am.text(), 1000);
            }
            if (am.hasToolExecutionRequests()) {
                StringBuilder sb = new StringBuilder("[助手调用] ");
                for (var req : am.toolExecutionRequests()) {
                    sb.append(req.name()).append('(').append(truncate(req.arguments(), 200)).append(") ");
                }
                return sb.toString();
            }
            return null;
        }
        if (msg instanceof ToolExecutionResultMessage trm) {
            return "[工具结果:" + trm.toolName() + "] " + truncate(trm.text(), 500);
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /** 供测试与日志使用：判断块是否属于逐字保留层 */
    static boolean isVerbatim(ContextBlock block) {
        return block.retention() == Retention.VERBATIM || block.kind() == BlockKind.USER_INPUT;
    }
}
