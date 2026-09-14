package cn.kong.eon.tool;

import java.util.Optional;

/**
 * 提问通道：把问题交付给用户并同步等待答案。
 * 「发事件」与「等答案」由调度器一并装配，工具只看到这一次调用——它拿不到 RunContext，不该自己 emit。
 */
@FunctionalInterface
public interface QuestionChannel {

    /** 阻塞到用户回答；返回空表示没等到答案（超时或任务被中断）。 */
    Optional<InteractionAnswer> ask(InteractionRequest request);
}
