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
 * <p>
 * 每个问题包含 id、prompt、options（≥2），可选 allow_multiple。
 * 通过 {@link InteractionCallback} 暂停 Agent，等待 HTTP 端点提交答案后恢复。
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

        InteractionCallback callback = context.interactionCallback();
        if (callback == null) {
            return ToolOutcome.failure("交互回调不可用，无法向用户提问。");
        }

        return executeViaCallback(arguments, callback, state, title);
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
}
