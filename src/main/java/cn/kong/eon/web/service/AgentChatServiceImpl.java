package cn.kong.eon.web.service;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.engine.AgentEngine;
import cn.kong.eon.event.AgentEventListener;
import cn.kong.eon.runtime.RunContext;
import cn.kong.eon.runtime.SessionRegistry;
import cn.kong.eon.runtime.SessionScope;
import cn.kong.eon.runtime.TaskScope;
import cn.kong.eon.store.index.SessionIndexStore;
import cn.kong.eon.store.index.SessionIndexStore.SessionSummary;
import cn.kong.eon.web.dto.ChatRequest;
import cn.kong.eon.web.dto.RunResult;
import cn.kong.eon.web.exception.SessionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 对话编排：解析会话 → 取缓存上下文 → 组装 RunContext → 调引擎 → 释放状态。
 */
@Service
public class AgentChatServiceImpl implements AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatServiceImpl.class);

    private final AgentConfig config;
    private final SessionIndexStore sessionIndexStore;
    private final SessionRegistry registry;
    private final AgentEngine agent;

    public AgentChatServiceImpl(AgentConfig config,
                                SessionIndexStore sessionIndexStore,
                                SessionRegistry registry,
                                AgentEngine agent) {
        this.config = config;
        this.sessionIndexStore = sessionIndexStore;
        this.registry = registry;
        this.agent = agent;
    }

    @Override
    public RunResult chat(ChatRequest request, List<AgentEventListener> listeners) {
        String requestedId = request.sessionId();
        String userId = request.userId() != null ? request.userId() : "default";
        boolean isNew = requestedId == null || requestedId.isBlank();

        // 先校验输入再取会话：空输入不再凭空创建会话并落库
        String userInput = request.message();
        if (userInput == null || userInput.isBlank()) {
            return new RunResult("输入不能为空。", requestedId);
        }

        SessionSummary resumed = null;
        if (!isNew) {
            resumed = sessionIndexStore.find(userId, requestedId)
                    .orElseThrow(() -> new SessionNotFoundException(requestedId));
        }

        String sessionId = isNew ? UUID.randomUUID().toString() : requestedId;

        // 先落索引再占用会话：反过来的话，insert 抛异常时 acquire 已经发生，
        // 而此时还没进 try 块，release 不会执行，会话会卡在 RUNNING 直到 TTL 淘汰（默认 24 小时）
        if (isNew) {
            sessionIndexStore.insert(sessionId, userId, deriveTitle(userInput));
        } else {
            // 已有会话收到新用户消息，递增用户消息计数
            sessionIndexStore.incrementUserMessageCount(sessionId);
        }

        // 缓存命中则跳过账本回放；同 session 已有任务在跑时按 busyPolicy 拒绝或排队
        SessionScope scope = registry.acquire(sessionId, resumed);

        RunContext ctx = null;
        try {
            TaskScope task = new TaskScope(userInput, config.getLoopDetect());
            ctx = new RunContext(scope, task, listeners);

            log.info("=== 会话 {} 任务开始 ===", sessionId);
            log.info("用户输入: {}", userInput.length() > 200 ? userInput.substring(0, 200) + "..." : userInput);

            String output = agent.run(ctx);

            log.info("=== 会话 {} 任务结束, 本次 {} 轮, 会话累计 {} tokens ===",
                    sessionId, task.turnCount(), scope.usageAccum().getTotalTokens());
            return new RunResult(output, sessionId);
        } finally {
            try {
                // 任务结束后更新索引：message_count 取账本实际行数
                sessionIndexStore.touch(sessionId, scope.ledger().getMessageCount());
                registry.release(sessionId);
            } finally {
                if (ctx != null) {
                    ctx.close();
                }
            }
        }
    }

    @Override
    public boolean interrupt(String sessionId) {
        return registry.find(sessionId)
                .map(scope -> {
                    RunContext current = scope.currentRun();
                    if (current == null) {
                        return false;
                    }
                    current.task().requestInterrupt();
                    return true;
                })
                .orElse(false);
    }

    /** 从用户输入派生会话标题（前 10 字符）。 */
    private static String deriveTitle(String userInput) {
        if (userInput == null) return "新会话";
        return userInput.length() <= 10 ? userInput : userInput.substring(0, 10);
    }
}
