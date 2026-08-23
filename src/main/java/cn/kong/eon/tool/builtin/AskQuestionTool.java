package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolOutcome;
import cn.kong.eon.tool.InteractionCallback;
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

    /** API 模式标志，设为 true 后不再阻塞 stdin。 */
    private static volatile boolean apiMode = false;

    /** CLI 模式 Scanner，懒加载。API 模式下不创建，避免资源泄漏。 */
    private Scanner scanner;
    private final Scanner providedScanner;

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

    @SuppressWarnings("unchecked")
    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();

        Map<String, Object> optionProps = new LinkedHashMap<>();
        optionProps.put("id", Map.of("type", "string", "description", "此选项的唯一标识符。", "required", true));
        optionProps.put("label", Map.of("type", "string", "description", "此选项的显示文本。", "required", true));

        Map<String, Object> questionProps = new LinkedHashMap<>();
        questionProps.put("id", Map.of("type", "string", "description", "此问题的唯一标识符。", "required", true));
        questionProps.put("prompt", Map.of("type", "string", "description", "要显示的问题文本。", "required", true));
        questionProps.put("options", Map.of("type", "array", "description", "答案选项（至少 2 个）。", "required", true, "items", optionProps));
        questionProps.put("allow_multiple", Map.of("type", "boolean", "description", "为 true 时允许用户多选。默认：false。"));

        props.put("questions", Map.of("type", "array", "description", "要呈现给用户的问题数组（至少 1 个）。", "required", true, "items", questionProps));
        props.put("title", Map.of("type", "string", "description", "问题表单的可选标题。"));

        String desc = "向用户收集结构化的多选答案。提供一个或多个带选项的问题，在适合多选时设置 allow_multiple。"
                + "当你需要通过结构化的问题格式从用户处收集特定信息时使用此工具。"
                + "每个问题应包含：唯一 id；清晰的提示文本；至少 2 个选项；可选的 allow_multiple 标志。";

        return new ToolDescriptor(
                "AskQuestion",
                desc,
                ToolPermission.READONLY,
                ToolDescriptor.buildSpec("AskQuestion", desc, props),
                new AskQuestionTool()
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        Object questionsObj = arguments.get("questions");
        if (!(questionsObj instanceof List<?> rawQuestions) || rawQuestions.isEmpty()) {
            return ToolOutcome.failure("缺少或空的 'questions' 参数");
        }

        String title = (String) arguments.get("title");

        // API 模式：通过 InteractionCallback 暂停 Agent
        if (apiMode && context.interactionCallback() != null) {
            return executeViaCallback(arguments, context.interactionCallback(), state, title);
        }

        // CLI 模式：直接从 stdin 读取
        if (apiMode) {
            // API 模式但无 callback（不应发生，但作为降级处理）
            return ToolOutcome.failure(
                    "API 模式暂不支持交互式提问，请直接在消息中提供用户可选项的信息。");
        }

        // 懒加载 Scanner：仅在 CLI 模式首次使用时创建
        if (scanner == null) {
            scanner = (providedScanner != null) ? providedScanner : new Scanner(System.in);
        }
        return executeViaStdin(rawQuestions, title);
    }

    /**
     * API 模式：通过回调接口提交问题并阻塞等待答案。
     */
    @SuppressWarnings("unchecked")
    private ToolOutcome executeViaCallback(Map<String, Object> arguments,
                                           InteractionCallback callback,
                                           SessionState state, String title) {
        List<Map<String, Object>> questions = (List<Map<String, Object>>) arguments.get("questions");

        log.info("AskQuestion via callback: {} questions, session={}", questions.size(), state.getSessionId());

        Map<String, String> answers = callback.askQuestions(questions, title);

        // 将答案格式化为输出文本
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

    /**
     * CLI 模式：从 stdin 读取用户选择。
     */
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
}
