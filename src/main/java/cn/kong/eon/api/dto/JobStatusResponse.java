package cn.kong.eon.api.dto;

import java.time.Instant;

/**
 * 异步任务状态查询响应。
 */
public class JobStatusResponse {
    private String jobId;
    private String sessionId;
    private String status;
    private String reply;
    private String error;
    private int turnCount;
    private Instant createdAt;
    private Instant completedAt;

    public JobStatusResponse() {}

    public static JobStatusResponse from(cn.kong.eon.service.ChatJob job) {
        JobStatusResponse resp = new JobStatusResponse();
        resp.jobId = job.getJobId();
        resp.sessionId = job.getSessionId();
        resp.status = job.getStatus().name();
        resp.reply = job.getResult();
        resp.error = job.getError();
        resp.turnCount = job.getTurnCount();
        resp.createdAt = job.getCreatedAt();
        resp.completedAt = job.getCompletedAt();
        return resp;
    }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public int getTurnCount() { return turnCount; }
    public void setTurnCount(int turnCount) { this.turnCount = turnCount; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
