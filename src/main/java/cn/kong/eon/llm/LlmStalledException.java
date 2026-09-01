package cn.kong.eon.llm;

/**
 * LLM 调用连续失败后抛出的异常，表示模型不可用。
 */
public class LlmStalledException extends RuntimeException {
    public LlmStalledException(String message) {
        super(message);
    }
}
