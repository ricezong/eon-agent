package cn.kong.eon.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenUsageTest {

    @Test
    void zero_createsAllZeroUsage() {
        TokenUsage usage = TokenUsage.zero();
        assertThat(usage.getPromptTokens()).isZero();
        assertThat(usage.getCompletionTokens()).isZero();
        assertThat(usage.getTotalTokens()).isZero();
    }

    @Test
    void add_accumulatesAllFields() {
        TokenUsage base = new TokenUsage();
        base.setPromptTokens(100);
        base.setCompletionTokens(50);
        base.setTotalTokens(150);

        TokenUsage delta = new TokenUsage();
        delta.setPromptTokens(200);
        delta.setCompletionTokens(80);
        delta.setTotalTokens(280);

        base.add(delta);

        assertThat(base.getPromptTokens()).isEqualTo(300);
        assertThat(base.getCompletionTokens()).isEqualTo(130);
        assertThat(base.getTotalTokens()).isEqualTo(430);
    }

    @Test
    void add_zeroDeltaIsNoOp() {
        TokenUsage usage = new TokenUsage();
        usage.setTotalTokens(42);
        usage.add(TokenUsage.zero());
        assertThat(usage.getTotalTokens()).isEqualTo(42);
    }

    @Test
    void toString_containsAllFields() {
        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(10);
        usage.setCompletionTokens(5);
        usage.setTotalTokens(15);
        String s = usage.toString();
        assertThat(s).contains("prompt=10").contains("completion=5").contains("total=15");
    }
}
