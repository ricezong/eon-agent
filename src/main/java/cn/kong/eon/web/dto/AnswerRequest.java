package cn.kong.eon.web.dto;

import cn.kong.eon.tool.InteractionAnswer;

import java.util.List;

/** 提问答案提交请求。answers 与提问时的 question id 对应，缺项由后端按「未选择」渲染。 */
public record AnswerRequest(
        String sessionId,
        List<InteractionAnswer.AnswerItem> answers
) {

    public InteractionAnswer toAnswer() {
        return new InteractionAnswer(answers == null ? List.of() : answers);
    }
}
