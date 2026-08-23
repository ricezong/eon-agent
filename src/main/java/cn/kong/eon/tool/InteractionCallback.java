package cn.kong.eon.tool;

import java.util.List;
import java.util.Map;

/**
 * 用户交互回调接口。
 * <p>
 * 在 API 模式下，当 {@link AskQuestionTool} 需要向用户收集答案时，
 * 通过此接口将问题暂存并暂停 Agent 执行，等待用户通过 HTTP 端点提交答案后恢复。
 * <p>
 * CLI 模式下不使用此接口（直接从 stdin 读取）。
 */
public interface InteractionCallback {

    /**
     * 提交交互请求，阻塞等待用户答案。
     * <p>
     * 在 API 模式下，此方法将问题暂存到会话级 {@code PendingInteraction}，
     * 然后阻塞当前 Agent 线程（通过 {@code CompletableFuture.get()}），
     * 直到用户通过 {@code POST /api/v1/chat/{sessionId}/answer} 提交答案后唤醒。
     * <p>
     * Agent 线程在此方法中阻塞期间，HTTP 请求线程仍可查询交互状态
     * （{@code GET /api/v1/chat/{sessionId}/interaction}）获取待处理的问题信息。
     *
     * @param questions 问题列表，每个 Map 包含 id、prompt、options、allow_multiple
     * @param title     问题表单可选标题
     * @return 用户答案映射：questionId → 选中的 optionId（多个用逗号分隔）
     */
    Map<String, String> askQuestions(List<Map<String, Object>> questions, String title);
}
