package cn.kong.eon.tool;

import java.util.List;
import java.util.Map;

/**
 * 用户交互回调接口。
 * API 模式下，AskQuestionTool 通过此接口将问题暂存并暂停 Agent 执行，
 * 等待用户通过 HTTP 端点提交答案后恢复。CLI 模式下不使用此接口。
 */
public interface InteractionCallback {

    /**
     * 提交交互请求，阻塞等待用户答案。
     *
     * @param questions 问题列表，每个 Map 包含 id、prompt、options、allow_multiple
     * @param title     问题表单可选标题
     * @return 用户答案映射：questionId → 选中的 optionId（多个用逗号分隔）
     */
    Map<String, String> askQuestions(List<Map<String, Object>> questions, String title);
}
