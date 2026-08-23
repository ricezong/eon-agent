package cn.kong.eon.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步对话任务管理器。
 * <p>
 * 维护 jobId → {@link ChatJob} 映射，支持创建、查询、超时清理。
 */
@Component
public class JobManager {
    private static final Logger log = LoggerFactory.getLogger(JobManager.class);

    /** 任务默认保留时间：完成后保留 1 小时供查询 */
    private static final Duration JOB_RETENTION = Duration.ofHours(1);

    private final Map<String, ChatJob> jobs = new ConcurrentHashMap<>();

    /** 创建并注册新任务，返回 jobId。 */
    public ChatJob createJob(String sessionId, String userInput) {
        String jobId = "job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ChatJob job = new ChatJob(jobId, sessionId, userInput);
        jobs.put(jobId, job);
        log.info("Job created: {} for session={}", jobId, sessionId);
        return job;
    }

    /** 获取任务。 */
    public ChatJob get(String jobId) {
        return jobs.get(jobId);
    }

    /** 清理超时任务（完成后超过保留期）。 */
    public void cleanupExpired() {
        Instant threshold = Instant.now().minus(JOB_RETENTION);
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, ChatJob> entry : jobs.entrySet()) {
            ChatJob job = entry.getValue();
            if (job.isDone() && job.getCompletedAt() != null && job.getCompletedAt().isBefore(threshold)) {
                toRemove.add(entry.getKey());
            }
        }
        for (String id : toRemove) {
            jobs.remove(id);
        }
        if (!toRemove.isEmpty()) {
            log.info("Expired jobs cleaned: {} (total remaining: {})", toRemove.size(), jobs.size());
        }
    }
}
