package cn.kong.eon.tool;

import java.util.List;
import java.util.Map;

/**
 * 用户交互回调接口。API 模式下通过此接口暂停 Agent 并等待用户答案。
 */
public interface InteractionCallback {

    /**
     * 提交交互请求，阻塞等待用户答案。
     * @param questions 问题列表，每个 Map 含 id、prompt、options、allow_multiple
     * @return 答案映射：questionId → 选中的 optionId（多个用逗号分隔）
     */
    Map<String, String> askQuestions(List<Map<String, Object>> questions, String title);
}
