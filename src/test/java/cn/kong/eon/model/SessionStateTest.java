package cn.kong.eon.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStateTest {

    @Test
    void beginRun_resetsTaskLevelState() {
        SessionState state = SessionState.create("sess-1", "first input");
        state.setTurnCount(5);
        state.addNudge("some nudge");
        state.addFormatCorrection("some correction");
        state.setLastAssistantText("last reply");
        state.getCompressionState().setLastTurnCompressed(4);

        state.beginRun("second input");

        assertThat(state.getUserInput()).isEqualTo("second input");
        assertThat(state.getTurnCount()).isZero();
        assertThat(state.getPendingNudges()).isEmpty();
        assertThat(state.getFormatCorrections()).isEmpty();
        assertThat(state.getLastAssistantText()).isNull();
        assertThat(state.getCompressionState().getLastTurnCompressed()).isZero();
    }

    @Test
    void beginRun_preservesSessionLevelState() {
        SessionState state = SessionState.create("sess-1", "first input");

        state.getUsageAccum().setPromptTokens(100);
        state.getUsageAccum().setCompletionTokens(50);
        state.getUsageAccum().setTotalTokens(150);

        CompressionState cs = state.getCompressionState();
        cs.markSnipped("call_1");
        cs.markPruned("call_2");
        cs.setLastSummary("summary so far");
        cs.setSummarizedMessageCount(7);

        state.setTodoBeenUsed(true);
        state.setBudgetSoftTriggered(true);

        state.beginRun("second input");

        assertThat(state.getUsageAccum().getPromptTokens()).isEqualTo(100);
        assertThat(state.getUsageAccum().getCompletionTokens()).isEqualTo(50);
        assertThat(state.getUsageAccum().getTotalTokens()).isEqualTo(150);
        assertThat(state.getCompressionState().isSnipped("call_1")).isTrue();
        assertThat(state.getCompressionState().isPruned("call_2")).isTrue();
        assertThat(state.getCompressionState().getLastSummary()).isEqualTo("summary so far");
        assertThat(state.getCompressionState().getSummarizedMessageCount()).isEqualTo(7);
        assertThat(state.hasTodoBeenUsed()).isTrue();
        assertThat(state.isBudgetSoftTriggered()).isTrue();
    }
}
