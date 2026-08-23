package cn.kong.eon.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JobManager} 单元测试。
 * <p>
 * 覆盖任务创建、查询、过期清理。
 */
class JobManagerTest {

    @Test
    void createJob_should_register_and_return_job() {
        JobManager manager = new JobManager();

        ChatJob job = manager.createJob("session_1", "hello");

        assertThat(job).isNotNull();
        assertThat(job.getJobId()).startsWith("job_");
        assertThat(job.getSessionId()).isEqualTo("session_1");
        assertThat(job.getUserInput()).isEqualTo("hello");
        assertThat(job.getStatus()).isEqualTo(ChatJob.JobStatus.PENDING);
        assertThat(job.isDone()).isFalse();
    }

    @Test
    void get_should_return_existing_job() {
        JobManager manager = new JobManager();
        ChatJob job = manager.createJob("session_1", "hello");

        ChatJob fetched = manager.get(job.getJobId());

        assertThat(fetched).isSameAs(job);
    }

    @Test
    void get_should_return_null_for_nonexistent_job() {
        JobManager manager = new JobManager();

        assertThat(manager.get("nonexistent")).isNull();
    }

    @Test
    void job_should_transition_through_lifecycle() {
        JobManager manager = new JobManager();
        ChatJob job = manager.createJob("session_1", "hello");

        job.markRunning();
        assertThat(job.getStatus()).isEqualTo(ChatJob.JobStatus.RUNNING);

        job.markCompleted("任务完成", 5);
        assertThat(job.getStatus()).isEqualTo(ChatJob.JobStatus.COMPLETED);
        assertThat(job.getResult()).isEqualTo("任务完成");
        assertThat(job.getTurnCount()).isEqualTo(5);
        assertThat(job.isDone()).isTrue();
        assertThat(job.getCompletedAt()).isNotNull();
    }

    @Test
    void job_should_mark_failed() {
        ChatJob job = new ChatJob("job_1", "session_1", "hello");
        job.markFailed("出错了");

        assertThat(job.getStatus()).isEqualTo(ChatJob.JobStatus.FAILED);
        assertThat(job.getError()).isEqualTo("出错了");
        assertThat(job.isDone()).isTrue();
    }
}
