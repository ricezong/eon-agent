package cn.kong.eon.runtime;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.tool.InteractionAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 提问网关：ask_question 阻塞等答案的落点。一次会话同时最多一个待回答的问题，
 * 工具线程阻塞在 {@link #ask} 上，答案由 api 层投递。
 */
@Component
public class InteractionGateway {

    private static final Logger log = LoggerFactory.getLogger(InteractionGateway.class);

    private final ConcurrentHashMap<String, CompletableFuture<InteractionAnswer>> pending = new ConcurrentHashMap<>();
    private final long timeoutSeconds;

    public InteractionGateway(AgentConfig config) {
        this.timeoutSeconds = config.getInteraction().getTimeoutSeconds();
    }

    /** 阻塞到答案送达；超时或任务被中断时返回空，由工具自行决定兜底文案。 */
    public Optional<InteractionAnswer> ask(String sessionId) {
        CompletableFuture<InteractionAnswer> future = new CompletableFuture<>();
        pending.put(sessionId, future);
        try {
            return Optional.ofNullable(future.get(timeoutSeconds, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            log.info("[interaction] 会话 {} 等待用户回答超时（{}s）", sessionId, timeoutSeconds);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            return Optional.empty();
        } finally {
            pending.remove(sessionId, future);
        }
    }

    /** 投递答案。没有待回答的问题时返回 false——用户重复提交，或答案来得太晚。 */
    public boolean answer(String sessionId, InteractionAnswer answer) {
        CompletableFuture<InteractionAnswer> future = pending.get(sessionId);
        if (future == null) {
            return false;
        }
        log.info("[interaction] 会话 {} 收到用户回答", sessionId);
        return future.complete(answer);
    }

    /** 中断或会话释放时唤醒阻塞的工具线程，否则它会一直挂到超时。 */
    public void cancel(String sessionId) {
        CompletableFuture<InteractionAnswer> future = pending.remove(sessionId);
        if (future != null) {
            future.completeExceptionally(new CancellationException("run interrupted"));
        }
    }
}
