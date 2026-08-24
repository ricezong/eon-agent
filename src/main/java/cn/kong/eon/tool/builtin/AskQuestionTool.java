package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolOutcome;
import cn.kong.eon.tool.InteractionCallback;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * AskQuestion 工具：向用户收集结构化多选答案。
 * 每个问题包含 id、prompt、options（≥2），可选 allow_multiple。
 * <p>
 * CLI 实现：打印选项编号，读取用户选择（Scanner）。
 * API 实现：通过 {@link InteractionCallback} 暂停 Agent，等待 HTTP 端点提交答案后恢复。
 */
public class AskQuestionTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(AskQuestionTool.class);

    /** Schema 专用 POJO：选项输入。 */
    public record OptionInput(
            String id,
            String label
    ) {}

    /** Schema 专用 POJO：问题输入。 */
    public record QuestionInput(
            String id,
            String prompt,
            List<OptionInput> options,
            Boolean allow_multiple
    ) {}

    /** API 模式标志，设为 true 后不再阻塞 stdin。 */
    private static volatile boolean apiMode = false;

    /** CLI 模式 Scanner，懒加载。API 模式下不创建，避免资源泄漏。 */
    private Scanner scanner;
    private final Scanner providedScanner;
    /** 标记 scanner 是否为内部创建（非外部传入），用于 close() 时判断是否需要释放。 */
    private boolean scannerInternallyCreated;

    public AskQuestionTool() {
        this.providedScanner = null;
    }

    public AskQuestionTool(Scanner scanner) {
        this.providedScanner = scanner;
    }

    /** 设置 API 模式，全局生效。 */
    public static void setApiMode(boolean enabled) {
        apiMode = enabled;
        log.info("AskQuestionTool apiMode={}", enabled);
    }

    /** @Tool 注解方法：供 ToolSpecifications 扫描生成 Schema。 */
    @Tool(name = "AskQuestion", value = {
            "向用户收集结构化的多选答案。提供一个或多个带选项的问题，在适合多选时设置 allow_multiple。",
            "当你需要通过结构化的问题格式从用户处收集特定信息时使用此工具。",
            "每个问题应包含：唯一 id；清晰的提示文本；至少 2 个选项；可选的 allow_multiple 标志。"
    })
    public String askQuestion(
            @P(name = "questions", description = "要呈现给用户的问题数组（至少 1 个）。每个问题包含 id（唯一标识符）、prompt（问题文本）、options（答案选项数组，每个选项含 id 和 label，至少 2 个）、allow_multiple（是否允许多选，默认 false）。") List<QuestionInput> questions,
            @P(name = "title", description = "问题表单的可选标题。") String title
    ) {
        return null;
    }

    public static ToolDescriptor descriptor() {
        return ToolDescriptor.fromAnnotated(new AskQuestionTool(), ToolPermission.READONLY);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        Object questionsObj = arguments.get("questions");
        if (!(questionsObj instanceof List<?> rawQuestions) || rawQuestions.isEmpty()) {
            return ToolOutcome.failure("缺少或空的 'questions' 参数");
        }

        String title = (String) arguments.get("title");

        if (apiMode && context.interactionCallback() != null) {
            return executeViaCallback(arguments, context.interactionCallback(), state, title);
        }

        if (apiMode) {
            // API 模式但无 callback（不应发生，但作为降级处理）
            return ToolOutcome.failure(
                    "API 模式暂不支持交互式提问，请直接在消息中提供用户可选项的信息。");
        }

        if (scanner == null) {
            if (providedScanner != null) {
                scanner = providedScanner;
            } else {
                scanner = new Scanner(System.in);
                scannerInternallyCreated = true;
            }
        }
        return executeViaStdin(rawQuestions, title);
    }

    @SuppressWarnings("unchecked")
    private ToolOutcome executeViaCallback(Map<String, Object> arguments,
                                           InteractionCallback callback,
                                           SessionState state, String title) {
        List<Map<String, Object>> questions = (List<Map<String, Object>>) arguments.get("questions");

        log.info("AskQuestion via callback: {} questions, session={}", questions.size(), state.getSessionId());

        Map<String, String> answers = callback.askQuestions(questions, title);

        StringBuilder output = new StringBuilder();
        if (title != null && !title.isBlank()) {
            output.append("--- ").append(title).append(" ---\n\n");
        }
        for (Map.Entry<String, String> entry : answers.entrySet()) {
            output.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }

        log.info("AskQuestion callback collected {} answers", answers.size());
        return ToolOutcome.success(output.toString());
    }

    private ToolOutcome executeViaStdin(List<?> rawQuestions, String title) {
        StringBuilder output = new StringBuilder();
        if (title != null && !title.isBlank()) {
            output.append("--- ").append(title).append(" ---\n\n");
        }

        Map<String, String> answers = new LinkedHashMap<>();

        for (Object qObj : rawQuestions) {
            if (!(qObj instanceof Map<?, ?> qMap)) continue;

            String qId = String.valueOf(qMap.get("id"));
            String prompt = String.valueOf(qMap.get("prompt"));
            boolean allowMultiple = Boolean.TRUE.equals(qMap.get("allow_multiple"));

            Object optionsObj = qMap.get("options");
            if (!(optionsObj instanceof List<?> options) || options.size() < 2) {
                return ToolOutcome.failure("问题 '" + qId + "' 至少需要 2 个选项");
            }

            System.out.println();
            System.out.println("  ┌──────────────────────────────────────────");
            System.out.println("  │ [问题] " + prompt);
            if (allowMultiple) {
                System.out.println("  │ (可多选，用逗号分隔)");
            }

            List<String> optionIds = new ArrayList<>();
            List<String> optionLabels = new ArrayList<>();
            for (int i = 0; i < options.size(); i++) {
                Object optObj = options.get(i);
                if (!(optObj instanceof Map<?, ?> optMap)) continue;
                String optId = String.valueOf(optMap.get("id"));
                String optLabel = String.valueOf(optMap.get("label"));
                optionIds.add(optId);
                optionLabels.add(optLabel);
                System.out.println("  │  " + (i + 1) + ". " + optLabel + " [" + optId + "]");
            }
            System.out.println("  └──────────────────────────────────────────");
            System.out.print("  请选择" + (allowMultiple ? "（数字，逗号分隔）" : "（数字）") + ": ");

            String input = scanner.nextLine().trim();

            List<String> selectedIds = new ArrayList<>();
            if (allowMultiple) {
                for (String part : input.split("[,，\\s]+")) {
                    int idx = parseChoice(part, optionIds.size());
                    if (idx >= 0) {
                        selectedIds.add(optionIds.get(idx));
                    }
                }
            } else {
                int idx = parseChoice(input, optionIds.size());
                if (idx >= 0) {
                    selectedIds.add(optionIds.get(idx));
                }
            }

            if (selectedIds.isEmpty()) {
                System.out.println("  无效选择，跳过此问题。");
                selectedIds.add("（未作答）");
            }

            String answer = String.join(", ", selectedIds);
            answers.put(qId, answer);
            output.append(qId).append(": ").append(answer).append("\n");
        }

        log.info("AskQuestion collected {} answers", answers.size());
        return ToolOutcome.success(output.toString());
    }

    private int parseChoice(String input, int max) {
        try {
            int n = Integer.parseInt(input.trim());
            if (n >= 1 && n <= max) return n - 1;
        } catch (NumberFormatException ignored) {}
        return -1;
    }

    /**
     * 释放内部创建的 Scanner 资源。
     * <p>
     * 仅关闭内部创建的 Scanner；外部传入的 providedScanner 由调用方管理。
     * 注意：Scanner 包装 System.in 时，close() 会关闭底层 System.in 流，
     * 因此对内部创建的 Scanner 不调用 close()，仅置 null 释放引用，
     * 由 GC 在后续回收。
     */
    @Override
    public void close() {
        if (scannerInternallyCreated && scanner != null) {
            // 不调用 scanner.close()，因为会关闭 System.in
            // 仅释放引用，让 GC 回收
            scanner = null;
            scannerInternallyCreated = false;
            log.debug("AskQuestionTool internal Scanner reference released");
        }
    }
}
