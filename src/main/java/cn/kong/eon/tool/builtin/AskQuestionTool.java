package cn.kong.eon.tool.builtin;

import cn.kong.eon.tool.ToolPermission;
import cn.kong.eon.tool.ToolRuntime;
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
 * AskQuestion 工具：向用户收集结构化多选答案。通过交互回调暂停 Agent，等待用户恢复。
 */
public class AskQuestionTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(AskQuestionTool.class);

    /** 选项输入 */
    public record OptionInput(
            String id,
            String label
    ) {
    }

    /** 问题输入 */
    public record QuestionInput(
            String id,
            String prompt,
            List<OptionInput> options,
            Boolean allow_multiple
    ) {
    }

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

    /**
     * 交互回调目前全项目无实现（{@link InteractionCallback} 只是接口，没有任何注入路径），
     * 因此本工具恒定返回失败。这不是降级——改造前同样如此，只是当时要等到运行期
     * 才发现 callback 为 null。此处前移为显式失败，避免误以为能力可用。
     * <p>
     * 接入方式：给 {@link ToolRuntime} 加回 callback 字段并在
     * {@code ToolCallDispatcher} 装配，然后恢复下面的回调分支。
     */
    @Override
    public ToolOutcome execute(Map<String, Object> arguments, ToolRuntime runtime) {
        Object questionsObj = arguments.get("questions");
        if (!(questionsObj instanceof List<?> rawQuestions) || rawQuestions.isEmpty()) {
            return ToolOutcome.failure("缺少或空的 'questions' 参数");
        }

        log.info("AskQuestion 被调用但交互回调未接入: {} 个问题, 会话={}",
                rawQuestions.size(), runtime.sessionId());
        return ToolOutcome.failure("交互回调不可用，无法向用户提问。");
    }
}
