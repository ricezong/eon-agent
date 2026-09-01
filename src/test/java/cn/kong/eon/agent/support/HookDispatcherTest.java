package cn.kong.eon.agent.support;

import cn.kong.eon.agent.hook.*;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HookDispatcherTest {

    private StopStateMachine createStopStateMachine() {
        AgentConfig config = new AgentConfig();
        return new StopStateMachine(config, new TurnLogger(config));
    }

    @Test
    void dispatchPreModel_noStop_returnsContinue() {
        SessionState state = SessionState.create("s1", "test");
        var hook = new TestPreModelHook(true, HookResult.ok());
        StopStateMachine sm = createStopStateMachine();

        TurnOutcome result = HookDispatcher.dispatchPreModel(
                List.of(hook), state, null, sm);

        assertThat(result).isInstanceOf(TurnOutcome.Continue.class);
    }

    @Test
    void dispatchPreModel_stopContinuesToNextHook() {
        SessionState state = SessionState.create("s1", "test");
        var hook1 = new TestPreModelHook(true, HookResult.stop(
                new StopReason(StopCategory.BUDGET_EXCEEDED, "budget")), "hook1");
        var hook2 = new TestPreModelHook(true, HookResult.ok(), "hook2");
        StopStateMachine sm = createStopStateMachine();

        TurnOutcome result = HookDispatcher.dispatchPreModel(
                List.of(hook1, hook2), state, null, sm);

        // PreModel: hook1 stop → immediate Exit
        assertThat(hook1.wasCalled).isTrue();
        assertThat(hook2.wasCalled).isFalse();
        assertThat(result).isInstanceOf(TurnOutcome.Exit.class);
    }

    @Test
    void dispatch_inactiveHookIsSkipped() {
        SessionState state = SessionState.create("s1", "test");
        var hook = new TestPreModelHook(false, HookResult.ok());
        StopStateMachine sm = createStopStateMachine();

        TurnOutcome result = HookDispatcher.dispatchPreModel(
                List.of(hook), state, null, sm);

        assertThat(result).isInstanceOf(TurnOutcome.Continue.class);
        assertThat(hook.wasCalled).isFalse();
    }

    @Test
    void dispatchPostModel_stopReturnsExit() {
        SessionState state = SessionState.create("s1", "test");
        var hook1 = new TestPostModelHook(true, HookResult.stop(
                new StopReason(StopCategory.LOOP_DETECTED, "loop")));
        var hook2 = new TestPostModelHook(true, HookResult.ok());
        StopStateMachine sm = createStopStateMachine();

        TurnOutcome result = HookDispatcher.dispatchPostModel(
                List.of(hook1, hook2), state, null, sm);

        assertThat(hook1.wasCalled).isTrue();
        assertThat(hook2.wasCalled).isFalse();
        assertThat(result).isInstanceOf(TurnOutcome.Exit.class);
        assertThat(((TurnOutcome.Exit) result).output()).contains("检测到死循环");
    }

    @Test
    void dispatchPostModel_noStop_returnsContinue() {
        SessionState state = SessionState.create("s1", "test");
        var hook = new TestPostModelHook(true, HookResult.ok());
        StopStateMachine sm = createStopStateMachine();

        TurnOutcome result = HookDispatcher.dispatchPostModel(
                List.of(hook), state, null, sm);

        assertThat(result).isInstanceOf(TurnOutcome.Continue.class);
    }

    // ===== Test Hook Implementations =====

    static class TestPreModelHook implements Hook.PreModelHook {
        private final boolean active;
        private final HookResult result;
        private final String hookName;
        boolean wasCalled = false;

        TestPreModelHook(boolean active, HookResult result) {
            this(active, result, "hook1");
        }

        TestPreModelHook(boolean active, HookResult result, String hookName) {
            this.active = active;
            this.result = result;
            this.hookName = hookName;
        }

        @Override
        public String name() { return hookName; }

        @Override
        public boolean active(SessionState state) { return active; }

        @Override
        public HookResult beforeModelCall(SessionState state, cn.kong.eon.agent.context.ContextBuilder ctx) {
            wasCalled = true;
            return result;
        }
    }

    static class TestPostModelHook implements Hook.PostModelHook {
        private final boolean active;
        private final HookResult result;
        boolean wasCalled = false;

        TestPostModelHook(boolean active, HookResult result) {
            this.active = active;
            this.result = result;
        }

        @Override
        public String name() { return "hook1"; }

        @Override
        public boolean active(SessionState state) { return active; }

        @Override
        public HookResult afterModelCall(SessionState state, cn.kong.eon.llm.LlmResponse response) {
            wasCalled = true;
            return result;
        }
    }
}
