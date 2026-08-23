package cn.kong.eon.api.dto;

import java.util.Map;

/**
 * 用户提交交互答案的请求。
 */
public class AskAnswerRequest {
    /** 问题 ID → 选中的 optionId（多个用逗号分隔） */
    private Map<String, String> answers;

    public Map<String, String> getAnswers() { return answers; }
    public void setAnswers(Map<String, String> answers) { this.answers = answers; }
}
