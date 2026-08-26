package cn.kong.eon.tool;

import java.util.*;

/**
 * CLI 模式的交互回调实现。通过 stdin 向用户展示问题并读取答案。
 * <p>
 * 当 {@link AskQuestionTool} 需要向用户收集答案时，此实现直接在终端
 * 打印问题和选项，等待用户输入编号或选项文本后返回结果。
 */
public class CliInteractionCallback implements InteractionCallback {

    private final Scanner scanner;

    public CliInteractionCallback(Scanner scanner) {
        this.scanner = scanner;
    }

    /**
     * 获取共享的 Scanner，供 CLI 循环复用（避免多个 Scanner 争抢 System.in）。
     */
    public Scanner getScanner() {
        return scanner;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> askQuestions(List<Map<String, Object>> questions, String title) {
        Map<String, String> answers = new LinkedHashMap<>();

        if (title != null && !title.isBlank()) {
            System.out.println();
            System.out.println("┌─ " + title + " ─────────────────────");
        }

        for (Map<String, Object> question : questions) {
            String qId = (String) question.get("id");
            String prompt = (String) question.get("prompt");
            List<Map<String, Object>> options = (List<Map<String, Object>>) question.get("options");
            boolean allowMultiple = Boolean.TRUE.equals(question.get("allow_multiple"));

            System.out.println();
            System.out.println("│ " + (prompt != null ? prompt : qId));

            if (options == null || options.isEmpty()) {
                System.out.print("│ > ");
                System.out.flush();
                String input = scanner.nextLine().trim();
                answers.put(qId, input);
                continue;
            }

            for (int i = 0; i < options.size(); i++) {
                Map<String, Object> opt = options.get(i);
                String label = (String) opt.get("label");
                String optId = (String) opt.get("id");
                System.out.printf("│  [%d] %s  (%s)%n", i + 1, label != null ? label : optId, optId);
            }

            if (allowMultiple) {
                System.out.print("│ 请输入选项编号，多个用逗号分隔（如 1,3）: ");
            } else {
                System.out.print("│ 请输入选项编号: ");
            }
            System.out.flush();

            String input = scanner.nextLine().trim();
            List<String> selectedIds = parseSelection(input, options);
            answers.put(qId, String.join(",", selectedIds));
        }

        if (title != null && !title.isBlank()) {
            System.out.println("└──────────────────────────────────────");
        }

        return answers;
    }

    /**
     * 解析用户输入的选项编号，返回对应的 optionId 列表。
     * 支持纯数字编号（1,2,3）或直接输入 optionId。
     */
    @SuppressWarnings("unchecked")
    private List<String> parseSelection(String input, List<Map<String, Object>> options) {
        List<String> result = new ArrayList<>();
        if (input == null || input.isBlank()) return result;

        for (String part : input.split("[,，\\s]+")) {
            part = part.trim();
            if (part.isEmpty()) continue;

            // 尝试按数字编号匹配
            try {
                int idx = Integer.parseInt(part);
                if (idx >= 1 && idx <= options.size()) {
                    String optId = (String) options.get(idx - 1).get("id");
                    if (optId != null) {
                        result.add(optId);
                        continue;
                    }
                }
            } catch (NumberFormatException ignored) {
            }

            // 尝试按 optionId 匹配
            for (Map<String, Object> opt : options) {
                String optId = (String) opt.get("id");
                if (part.equals(optId)) {
                    result.add(optId);
                    break;
                }
            }
        }

        return result;
    }
}
