package cn.kong.eon.api.dto;

/**
 * 异步对话提交响应。
 */
public class AsyncChatResponse {
    private String jobId;
    private String sessionId;
    private String status;

    public AsyncChatResponse() {}

    public AsyncChatResponse(String jobId, String sessionId, String status) {
        this.jobId = jobId;
        this.sessionId = sessionId;
        this.status = status;
    }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
