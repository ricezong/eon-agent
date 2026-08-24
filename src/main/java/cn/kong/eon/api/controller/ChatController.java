package cn.kong.eon.api.controller;

import cn.kong.eon.api.dto.*;
import cn.kong.eon.api.exception.SessionBusyException;
import cn.kong.eon.service.AgentService;
import cn.kong.eon.service.ChatJob;
import cn.kong.eon.service.JobManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.validation.Valid;

/**
 * 对话 API。
 * <p>
 * POST /api/v1/chat        — 同步对话（阻塞直到 Agent 完成本轮循环）
 * POST /api/v1/chat/async  — 异步对话（提交后立即返回 jobId）
 * GET  /api/v1/chat/jobs/{jobId} — 查询异步任务状态
 * GET  /api/v1/stream      — SSE 流式对话（实时推送 Agent 执行事件）
 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentService agentService;
    private final JobManager jobManager;

    public ChatController(AgentService agentService, JobManager jobManager) {
        this.agentService = agentService;
        this.jobManager = jobManager;
    }

    /**
     * 同步对话。
     * <p>
     * 如果请求中包含 sessionId，则在已有会话中继续对话；
     * 如果不包含 sessionId，则自动创建新会话。
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }

        String sessionId = request.getSessionId();
        String reply;
        int turnCount;

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = agentService.createSession(request.getMessage());
            reply = agentService.chat(sessionId, request.getMessage());
        } else {
            reply = agentService.chat(sessionId, request.getMessage());
        }

        var session = agentService.getSession(sessionId);
        turnCount = session != null ? session.getState().getTurnCount() : 0;

        return ResponseEntity.ok(new ChatResponse(sessionId, reply, turnCount));
    }

    /**
     * 异步对话。提交后立即返回 jobId，不阻塞 HTTP 请求。
     */
    @PostMapping("/chat/async")
    public ResponseEntity<AsyncChatResponse> chatAsync(@Valid @RequestBody ChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = agentService.createSession(request.getMessage());
        }

        if (agentService.getSession(sessionId) == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }

        ChatJob job = jobManager.createJob(sessionId, request.getMessage());
        agentService.chatAsync(job, sessionId, request.getMessage());

        return ResponseEntity.ok(new AsyncChatResponse(job.getJobId(), sessionId, job.getStatus().name()));
    }

    /**
     * 查询异步任务状态。
     */
    @GetMapping("/chat/jobs/{jobId}")
    public ResponseEntity<JobStatusResponse> getJobStatus(@PathVariable("jobId") String jobId) {
        ChatJob job = jobManager.get(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(JobStatusResponse.from(job));
    }

    /**
     * SSE 流式对话。
     * <p>
     * 通过 Server-Sent Events 实时推送 Agent 执行过程中的事件：
     * <ul>
     *   <li>RUN_START — Agent 开始运行</li>
     *   <li>TURN_START — Turn 开始</li>
     *   <li>LLM_RESPONSE — LLM 响应（思考文本 + 工具调用名）</li>
     *   <li>TOOL_START — 工具开始执行</li>
     *   <li>TOOL_RESULT — 工具执行完成</li>
     *   <li>TURN_END — Turn 结束</li>
     *   <li>DONE — 正常完成（含最终输出）</li>
     *   <li>TERMINATED — 被强制终止</li>
     *   <li>ERROR — 执行出错</li>
     * </ul>
     * <p>
     * 如果不传 sessionId，则自动创建新会话。
     *
     * @param sessionId 会话 ID（可选）
     * @param message   用户消息（作为 query param，因为 GET 请求无 body）
     */
    @GetMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter stream(
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "message") String message) {

        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }

        if (sessionId == null || sessionId.isBlank()) {
            return agentService.createAndStream(message);
        }

        return agentService.chatStream(sessionId, message);
    }

    /**
     * 查询会话的交互状态。
     * <p>
     * 如果 Agent 在执行过程中调用了 AskQuestion 工具，
     * 会话进入 PENDING 状态，此端点返回待处理的问题信息。
     *
     * @return 200 + 交互状态（pending=true 时含问题列表）
     */
    @GetMapping("/chat/{sessionId}/interaction")
    public ResponseEntity<InteractionResponse> getInteraction(@PathVariable("sessionId") String sessionId) {
        return ResponseEntity.ok(agentService.getInteraction(sessionId));
    }

    /**
     * 提交用户答案，恢复被暂停的 Agent 执行。
     * <p>
     * 当 Agent 通过 AskQuestion 暂停后，客户端通过此端点提交答案。
     * 答案提交后，Agent 从暂停点恢复执行。
     *
     * @return 200 + 确认信息
     */
    @PostMapping("/chat/{sessionId}/answer")
    public ResponseEntity<java.util.Map<String, String>> submitAnswer(
            @PathVariable("sessionId") String sessionId,
            @RequestBody AskAnswerRequest request) {

        if (request.getAnswers() == null || request.getAnswers().isEmpty()) {
            throw new IllegalArgumentException("answers 不能为空");
        }

        String result = agentService.submitAnswer(sessionId, request.getAnswers());
        return ResponseEntity.ok(java.util.Map.of("message", result));
    }
}
