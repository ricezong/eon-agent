package cn.kong.eon.llm;

/**
 * LLM 调用连续失败后抛出的异常。
 * 异常消息不含底层堆栈（堆栈已通过 log.error 记录），避免敏感信息泄露。
 */
public class LlmStalledException extends RuntimeException {
    public LlmStalledException(String message) {
        super(message);
    }
}
