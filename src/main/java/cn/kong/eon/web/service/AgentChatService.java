package cn.kong.eon.web.service;

import cn.kong.eon.event.AgentEventListener;
import cn.kong.eon.web.dto.ChatRequest;
import cn.kong.eon.web.dto.RunResult;
import cn.kong.eon.web.exception.SessionBusyException;
import cn.kong.eon.web.exception.SessionNotFoundException;

import java.util.List;

/**
 * 对话编排服务。
 * <p>
 * 职责：解析会话 → 从缓存取 {@code SessionScope} → 组装 {@code RunContext} → 调用引擎 → 释放状态。
 * 不感知 HTTP 细节（{@code SseEmitter} 是传输层概念，由 controller 包装成 {@link AgentEventListener} 传入）。
 */
public interface AgentChatService {

    /**
     * 执行一轮对话。事件通过 listeners 实时推送，方法返回时任务已结束。
     *
     * @throws SessionNotFoundException sessionId 在索引中不存在
     * @throws SessionBusyException     该会话正在执行另一个任务
     */
    RunResult chat(ChatRequest request, List<AgentEventListener> listeners);

    /** 请求中断指定会话的当前任务。 */
    boolean interrupt(String sessionId);
}
