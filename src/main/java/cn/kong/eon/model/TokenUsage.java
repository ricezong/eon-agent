package cn.kong.eon.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Token 用量累计。
 * 对应技术方案第 2.6 节。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TokenUsage {
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;

    public TokenUsage() {}

    public static TokenUsage zero() {
        return new TokenUsage();
    }

    public void add(TokenUsage other) {
        this.promptTokens += other.promptTokens;
        this.completionTokens += other.completionTokens;
        this.totalTokens += other.totalTokens;
    }

    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }

    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }

    @Override
    public String toString() {
        return String.format("TokenUsage{prompt=%d, completion=%d, total=%d}", promptTokens, completionTokens, totalTokens);
    }
}
