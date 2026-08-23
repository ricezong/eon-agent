package cn.kong.eon.service;

import cn.kong.eon.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 定时清理超时会话和过期任务。
 * <p>
 * 每 5 分钟执行一次：
 * <ul>
 *   <li>清理超过 30 分钟未活跃的会话</li>
 *   <li>清理已完成超过 1 小时的异步任务</li>
 * </ul>
 */
@Component
public class SessionCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(SessionCleanupScheduler.class);

    private final SessionManager sessionManager;
    private final JobManager jobManager;

    @Value("${eon.session.timeout-minutes:30}")
    private int sessionTimeoutMinutes;

    public SessionCleanupScheduler(SessionManager sessionManager, JobManager jobManager) {
        this.sessionManager = sessionManager;
        this.jobManager = jobManager;
    }

    /** 每 5 分钟清理超时会话。 */
    @Scheduled(fixedDelay = 300_000) // 5 minutes
    public void cleanupExpiredSessions() {
        Duration timeout = Duration.ofMinutes(sessionTimeoutMinutes);
        Instant threshold = Instant.now().minus(timeout);
        int count = sessionManager.cleanupExpired(threshold);
        if (count > 0) {
            log.info("Expired sessions cleaned: {} (timeout={}min)", count, sessionTimeoutMinutes);
        }
    }

    /** 每 5 分钟清理过期任务。 */
    @Scheduled(fixedDelay = 300_000) // 5 minutes
    public void cleanupExpiredJobs() {
        jobManager.cleanupExpired();
    }
}
