package cn.kong.eon.tool.builtin;

import cn.kong.eon.tool.InteractionAnswer;
import cn.kong.eon.tool.InteractionRequest;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolPermission;
import cn.kong.eon.tool.ToolResult;
import cn.kong.eon.tool.ToolRuntime;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ask_question 工具：向用户收集结构化答案。
 * 工具会阻塞在 execute 里等用户回答——答案即工具结果，本轮 run 拿到后继续执行，不中断会话。
 */
public class AskQuestionTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(AskQuestionTool.class);

    /** 每个问题的选项上限，与工具描述一致；超出部分直接截断，避免前端一次塞太多选项。 */
    private static final int MAX_OPTIONS = 4;

    /** 用户超时未答时的兜底：明确要求模型自行推进，避免它反复追问把任务卡死。 */
    private static final String NO_ANSWER =
            "用户未在限定时间内回答。请基于已有信息自行决策并继续推进；若确实缺少必要前提，用文字直接向用户说明，不要再调用本工具追问。";

    private static final TypeReference<List<QuestionInput>> QUESTIONS_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    private AskQuestionTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 选项输入。模型常会多给字段，忽略未知字段而不是直接失败。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptionInput(
            String id,
            String label
    ) {
    }

    /** 问题输入 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuestionInput(
            String id,
            String prompt,
            List<OptionInput> options,
            Boolean allow_multiple
    ) {
    }

    /** 校验收敛后的问题，同时供前端视图与答案渲染使用。 */
    private record Question(
            String id,
            String prompt,
            List<Option> options,
            boolean allowMultiple
    ) {
    }

    private record Option(String id, String label) {
    }

    /** 仅用于生成 ToolSpecification，LangChain4j 不会调用它。 */
    @Tool(name = "ask_question", value = {
            "向用户收集结构化的答案。提供一个或多个带选项的问题，在适合多选时设置 allow_multiple。",
            "当你需要通过结构化的问题格式从用户处收集特定信息时使用此工具。",
            "每个问题应包含：唯一 id；清晰的提示文本；2 到 4 个选项（最多 4 个，超出会被截断）；可选的 allow_multiple 标志。",
            "调用后会阻塞等待用户作答，答案作为工具结果返回；若用户超时未答，你会收到提示并应自行决策继续。"
    })
    public String askQuestion(
            @P(name = "questions", description = "要呈现给用户的问题数组（至少 1 个）。每个问题包含 id（唯一标识符）、prompt（问题文本）、options（答案选项数组，每个选项含 id 和 label，2 到 4 个，最多 4 个）、allow_multiple（是否允许多选，默认 false）。") List<QuestionInput> questions,
            @P(name = "title", description = "问题表单的可选标题。") String title
    ) {
        return null;
    }

    public static ToolDescriptor descriptor(ObjectMapper objectMapper) {
        return ToolDescriptor.fromAnnotated(new AskQuestionTool(objectMapper), ToolPermission.READONLY);
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolRuntime runtime) {
        if (runtime.questions() == null) {
            return ToolResult.failure("提问通道不可用，无法向用户提问。");
        }

        List<QuestionInput> inputs = parseQuestions(arguments.get("questions"));
        if (inputs.isEmpty()) {
            return ToolResult.failure("缺少或无法解析 'questions' 参数");
        }

        List<Question> questions = normalize(inputs);
        if (questions.isEmpty()) {
            return ToolResult.failure("'questions' 中没有有效的问题项");
        }

        String title = arguments.get("title") == null ? null : String.valueOf(arguments.get("title"));

        InteractionAnswer answer = runtime.questions()
                .ask(new InteractionRequest(title, toViews(questions)))
                .orElse(null);

        // 超时/中断不算工具失败：判失败会触发熔断，而这里只是没人回答
        return answer == null ? ToolResult.success(NO_ANSWER) : ToolResult.success(render(questions, answer));
    }

    /** 把嵌套的 questions 数组反序列化为强类型，解析失败按空处理。 */
    private List<QuestionInput> parseQuestions(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(raw, QUESTIONS_TYPE);
        } catch (IllegalArgumentException e) {
            log.warn("[ask_question] questions 反序列化失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 逐题校验并收敛；丢弃与截断都是策略行为，必须留日志。 */
    private List<Question> normalize(List<QuestionInput> inputs) {
        List<Question> result = new ArrayList<>();
        for (QuestionInput q : inputs) {
            if (q == null || q.prompt() == null || q.prompt().isBlank()) {
                log.warn("[ask_question] 丢弃缺少 prompt 的问题项: {}", q);
                continue;
            }

            List<OptionInput> rawOptions = q.options() == null ? List.of() : q.options();
            if (rawOptions.size() > MAX_OPTIONS) {
                log.warn("[ask_question] 问题 [{}] 选项数 {} 超过上限 {}，截断前 {} 个",
                        q.id(), rawOptions.size(), MAX_OPTIONS, MAX_OPTIONS);
                rawOptions = rawOptions.subList(0, MAX_OPTIONS);
            }

            List<Option> options = new ArrayList<>();
            for (OptionInput o : rawOptions) {
                if (o == null || o.label() == null) continue;
                options.add(new Option(o.id() == null ? "" : o.id(), o.label()));
            }

            result.add(new Question(q.id() == null ? "" : q.id(), q.prompt(), options,
                    Boolean.TRUE.equals(q.allow_multiple())));
        }
        return result;
    }

    /** 转成前端直接渲染的结构：questions[{id,prompt,options[{id,label}],allow_multiple}]。 */
    private List<Map<String, Object>> toViews(List<Question> questions) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (Question q : questions) {
            List<Map<String, String>> options = new ArrayList<>();
            for (Option o : q.options()) {
                options.add(Map.of("id", o.id(), "label", o.label()));
            }
            views.add(Map.<String, Object>of(
                    "id", q.id(),
                    "prompt", q.prompt(),
                    "options", options,
                    "allow_multiple", q.allowMultiple()));
        }
        return views;
    }

    /** 答案渲染成模型可读的文本；未选中的题目显式标注，避免模型误以为用户默许。 */
    private String render(List<Question> questions, InteractionAnswer answer) {
        Map<String, InteractionAnswer.AnswerItem> byId = new HashMap<>();
        if (answer.answers() != null) {
            for (InteractionAnswer.AnswerItem item : answer.answers()) {
                if (item != null && item.id() != null) {
                    byId.put(item.id(), item);
                }
            }
        }

        StringBuilder sb = new StringBuilder("用户已回答：\n");
        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            InteractionAnswer.AnswerItem item = byId.get(q.id());

            List<String> parts = new ArrayList<>();
            if (item != null && item.labels() != null) {
                for (String label : item.labels()) {
                    if (label != null && !label.isBlank()) parts.add(label);
                }
            }
            if (item != null && item.other() != null && !item.other().isBlank()) {
                parts.add(item.other().trim());
            }

            sb.append(i + 1).append(". ").append(q.prompt()).append('\n')
                    .append("   ").append(parts.isEmpty() ? "（未选择）" : String.join("、", parts))
                    .append('\n');
        }
        return sb.toString().trim();
    }
}
