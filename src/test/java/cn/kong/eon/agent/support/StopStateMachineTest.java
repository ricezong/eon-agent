package cn.kong.eon.agent.support;

import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmStalledException;
import cn.kong.eon.model.SessionState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StopStateMachineTest {

    private StopStateMachine createStateMachine(int maxSteps) {
        AgentConfig config = new AgentConfig();
        AgentConfig.LoopConfig loop = new AgentConfig.LoopConfig();
        loop.setMaxSteps(maxSteps);
        try {
            var loopField = AgentConfig.class.getDeclaredField("loop");
            loopField.setAccessible(true);
            loopField.set(config, loop);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new StopStateMachine(config, new TurnLogger(config));
    }

    private SessionState createState() {
        return SessionState.create("s1", "test");
    }

    @Test
    void handleMaxSteps_returnsExitWithMessage() {
        StopStateMachine sm = createStateMachine(30);
        SessionState state = createState();

        String output = sm.handleMaxSteps(state);

        assertThat(output).contains("最大步数");
    }

    @Test
    void handleLoopException_llmStalled_returnsExit() {
        StopStateMachine sm = createStateMachine(30);
        SessionState state = createState();

        String output = sm.handleLoopException(state, new LlmStalledException("model unavailable"));

        assertThat(output).contains("LLM");
    }

    @Test
    void handleLoopException_generalException_returnsExit() {
        StopStateMachine sm = createStateMachine(30);
        SessionState state = createState();

        String output = sm.handleLoopException(state, new RuntimeException("unexpected error"));

        assertThat(output).contains("unexpected error");
    }

    @Test
    void forceTerminate_returnsFormattedOutput() {
        StopStateMachine sm = createStateMachine(30);
        SessionState state = createState();
        state.incrementTurn();
        state.incrementTurn();

        String output = sm.forceTerminate(state, new StopReason(
                StopCategory.BUDGET_EXCEEDED, "budget exceeded"));

        assertThat(output).contains("预算超限");
        assertThat(output).contains("budget exceeded");
        assertThat(output).contains("2 轮");
    }
}
