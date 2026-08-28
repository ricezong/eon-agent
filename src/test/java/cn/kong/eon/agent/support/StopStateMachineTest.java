package cn.kong.eon.agent.support;

import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.agent.support.HookDispatcher.FireResult;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmStalledException;
import cn.kong.eon.model.SessionState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StopStateMachineTest {

    private StopStateMachine createStateMachine(int graceSteps, int maxSteps) {
        AgentConfig config = new AgentConfig();
        AgentConfig.BudgetConfig budget = new AgentConfig.BudgetConfig();
        budget.setGraceSteps(graceSteps);
        AgentConfig.LoopConfig loop = new AgentConfig.LoopConfig();
        loop.setMaxSteps(maxSteps);
        setConfigFields(config, budget, loop);

        // Use a temp JSONL for MessageFinalizer
        try {
            var jsonlPath = java.nio.file.Files.createTempFile("test_ssm", ".jsonl");
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            MessageFinalizer finalizer = new MessageFinalizer(
                    new cn.kong.eon.store.JsonlStore(jsonlPath, mapper));
            return new StopStateMachine(config, new TurnLogger(config), finalizer);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setConfigFields(AgentConfig config, AgentConfig.BudgetConfig budget, AgentConfig.LoopConfig loop) {
        try {
            var budgetField = AgentConfig.class.getDeclaredField("budget");
            budgetField.setAccessible(true);
            budgetField.set(config, budget);

            var loopField = AgentConfig.class.getDeclaredField("loop");
            loopField.setAccessible(true);
            loopField.set(config, loop);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SessionState createStateWithStop() {
        SessionState state = SessionState.create("s1", "test");
        state.setStopState(SessionState.StopState.none());
        return state;
    }

    @Test
    void handleStop_graceStepsZero_immediateExit() {
        StopStateMachine sm = createStateMachine(0, 30);
        SessionState state = createStateWithStop();

        FireResult result = sm.handleStop(null, state,
                new StopReason(StopCategory.BUDGET_EXCEEDED, "budget exceeded", 0));

        assertThat(result).isInstanceOf(FireResult.Exit.class);
        assertThat(((FireResult.Exit) result).output()).contains("预算超限");
        assertThat(((FireResult.Exit) result).output()).contains("budget exceeded");
    }

    @Test
    void handleStop_withGraceSteps_entersGracePeriod() {
        StopStateMachine sm = createStateMachine(3, 30);
        SessionState state = createStateWithStop();

        FireResult result = sm.handleStop(null, state,
                new StopReason(StopCategory.BUDGET_EXCEEDED, "budget soft", 3));

        assertThat(result).isInstanceOf(FireResult.Continue.class);
        assertThat(state.isStopRequested()).isTrue();
        assertThat(state.getStopState().getRemainingGraceSteps()).isEqualTo(3);
        assertThat(state.getPendingNudges()).isNotEmpty();
    }

    @Test
    void handleStop_alreadyInStop_addsNudge() {
        StopStateMachine sm = createStateMachine(3, 30);
        SessionState state = createStateWithStop();

        // First stop request
        sm.handleStop(null, state, new StopReason(StopCategory.BUDGET_EXCEEDED, "first", 3));
        int nudgesAfterFirst = state.getPendingNudges().size();

        // Second stop request
        sm.handleStop(null, state, new StopReason(StopCategory.LOOP_DETECTED, "second", 3));
        assertThat(state.getPendingNudges().size()).isGreaterThan(nudgesAfterFirst);
    }

    @Test
    void handleStop_alreadyInStop_noGraceLeft_returnsExit() {
        StopStateMachine sm = createStateMachine(1, 30);
        SessionState state = createStateWithStop();

        // First stop
        sm.handleStop(null, state, new StopReason(StopCategory.BUDGET_EXCEEDED, "first", 1));
        // Consume the grace step
        state.getStopState().consumeGraceStep();

        // Now no grace left
        FireResult result = sm.handleStop(null, state,
                new StopReason(StopCategory.LOOP_DETECTED, "second", 0));

        assertThat(result).isInstanceOf(FireResult.Exit.class);
    }

    @Test
    void consumeGraceStep_returnsContinueWhileGraceAvailable() {
        StopStateMachine sm = createStateMachine(3, 30);
        SessionState state = createStateWithStop();
        state.getStopState().request(new StopReason(StopCategory.BUDGET_EXCEEDED, "budget", 3));

        TurnRecord rec = new TurnRecord();
        TurnAction result = sm.consumeGraceStep(rec, state, "grace");

        assertThat(result).isInstanceOf(TurnAction.Continue.class);
        assertThat(state.getStopState().getRemainingGraceSteps()).isEqualTo(2);
    }

    @Test
    void consumeGraceStep_exhausted_returnsExit() {
        StopStateMachine sm = createStateMachine(1, 30);
        SessionState state = createStateWithStop();
        state.getStopState().request(new StopReason(StopCategory.BUDGET_EXCEEDED, "budget", 1));

        TurnRecord rec = new TurnRecord();
        // Consume the only grace step
        TurnAction result = sm.consumeGraceStep(rec, state, "grace");

        assertThat(result).isInstanceOf(TurnAction.Exit.class);
        assertThat(((TurnAction.Exit) result).output()).contains("预算超限");
    }

    @Test
    void handleMaxSteps_returnsExitWithMessage() {
        StopStateMachine sm = createStateMachine(3, 30);
        SessionState state = createStateWithStop();

        TurnAction result = sm.handleMaxSteps(state);

        assertThat(result).isInstanceOf(TurnAction.Exit.class);
        assertThat(((TurnAction.Exit) result).output()).contains("最大步数");
    }

    @Test
    void handleLoopException_llmStalled_returnsExit() {
        StopStateMachine sm = createStateMachine(3, 30);
        SessionState state = createStateWithStop();

        TurnAction result = sm.handleLoopException(state, new LlmStalledException("model unavailable"));

        assertThat(result).isInstanceOf(TurnAction.Exit.class);
        assertThat(((TurnAction.Exit) result).output()).contains("LLM");
    }

    @Test
    void handleLoopException_generalException_triesGracefulStop() {
        StopStateMachine sm = createStateMachine(3, 30);
        SessionState state = createStateWithStop();

        TurnAction result = sm.handleLoopException(state, new RuntimeException("unexpected error"));

        // With graceSteps=3, should enter grace period (Continue)
        assertThat(result).isInstanceOf(TurnAction.Continue.class);
        assertThat(state.isStopRequested()).isTrue();
    }
}
