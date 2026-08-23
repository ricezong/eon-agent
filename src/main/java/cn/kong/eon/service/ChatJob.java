package cn.kong.eon.service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * 异步对话任务的状态容器。
 * <p>
 * 由 {@link JobManager} 管理，生命周期：
 * <pre>
 * PENDING → RUNNING → COMPLETED
 *                  → FAILED
 * </pre>
 */
public class ChatJob {
    private final String jobId;
    private final String sessionId;
    private final String userInput;
    private final Instant createdAt;

    private volatile JobStatus status = JobStatus.PENDING;
    private volatile String result;
    private volatile String error;
    private volatile Instant completedAt;
    private volatile int turnCount;

    private final CompletableFuture<String> future = new CompletableFuture<>();

    public ChatJob(String jobId, String sessionId, String userInput) {
        this.jobId = jobId;
        this.sessionId = sessionId;
        this.userInput = userInput;
        this.createdAt = Instant.now();
    }

    public String getJobId() { return jobId; }
    public String getSessionId() { return sessionId; }
    public String getUserInput() { return userInput; }
    public Instant getCreatedAt() { return createdAt; }
    public JobStatus getStatus() { return status; }
    public String getResult() { return result; }
    public String getError() { return error; }
    public Instant getCompletedAt() { return completedAt; }
    public int getTurnCount() { return turnCount; }
    public CompletableFuture<String> getFuture() { return future; }

    public void markRunning() { this.status = JobStatus.RUNNING; }
    public void markCompleted(String result, int turnCount) {
        this.status = JobStatus.COMPLETED;
        this.result = result;
        this.turnCount = turnCount;
        this.completedAt = Instant.now();
        this.future.complete(result);
    }
    public void markFailed(String error) {
        this.status = JobStatus.FAILED;
        this.error = error;
        this.completedAt = Instant.now();
        this.future.completeExceptionally(new RuntimeException(error));
    }

    /** 任务是否已结束（成功或失败）。 */
    public boolean isDone() {
        return status == JobStatus.COMPLETED || status == JobStatus.FAILED;
    }

    public enum JobStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }
}
