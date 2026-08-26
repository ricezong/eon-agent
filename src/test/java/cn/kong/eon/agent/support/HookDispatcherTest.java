package cn.kong.eon.agent.support;

import cn.kong.eon.agent.hook.*;
import cn.kong.eon.agent.support.HookDispatcher.FireResult;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HookDispatcherTest {

    @Test
    void dispatchPreModel_noStop_returnsContinue() {
        SessionState state = SessionState.create("s1", "test");
        var hook = new TestPreModelHook(true, HookResult.ok());

        FireResult result = HookDispatcher.dispatchPreModel(
                List.of(hook), state,
                (h, s) -> h.beforeModelCall(s, null),
                reason -> new FireResult.Continue()
        );

        assertThat(result).isInstanceOf(FireResult.Continue.class);
    }

    @Test
    void dispatchPreModel_stopContinuesToNextHook() {
        SessionState state = SessionState.create("s1", "test");
        var hook1 = new TestPreModelHook(true, HookResult.stop(
                new StopReason(StopCategory.BUDGET_EXCEEDED, "budget", 3)));
        var hook2 = new TestPreModelHook(true, HookResult.ok(), "hook2");

        StringBuilder callOrder = new StringBuilder();

        FireResult result = HookDispatcher.dispatchPreModel(
                List.of(hook1, hook2), state,
                (h, s) -> {
                    callOrder.append(h.name());
                    return h.beforeModelCall(s, null);
                },
                reason -> new FireResult.Continue()
        );

        // Both hooks should be called
        assertThat(callOrder.toString()).contains("hook1").contains("hook2");
        assertThat(result).isInstanceOf(FireResult.Continue.class);
    }

    @Test
    void dispatchPreModel_exitStopsIteration() {
        SessionState state = SessionState.create("s1", "test");
        var hook1 = new TestPreModelHook(true, HookResult.stop(
                new StopReason(StopCategory.BUDGET_EXCEEDED, "budget", 0)));
        var hook2 = new TestPreModelHook(true, HookResult.ok(), "hook2");

        StringBuilder callOrder = new StringBuilder();

        FireResult result = HookDispatcher.dispatchPreModel(
                List.of(hook1, hook2), state,
                (h, s) -> {
                    callOrder.append(h.name());
                    return h.beforeModelCall(s, null);
                },
                reason -> new FireResult.Exit("forced exit")
        );

        assertThat(callOrder.toString()).isEqualTo("hook1");
        assertThat(result).isInstanceOf(FireResult.Exit.class);
        assertThat(((FireResult.Exit) result).output()).isEqualTo("forced exit");
    }

    @Test
    void dispatch_inactiveHookIsSkipped() {
        SessionState state = SessionState.create("s1", "test");
        var hook = new TestPreModelHook(false, HookResult.ok());

        FireResult result = HookDispatcher.dispatchPreModel(
                List.of(hook), state,
                (h, s) -> h.beforeModelCall(s, null),
                reason -> new FireResult.Continue()
        );

        assertThat(result).isInstanceOf(FireResult.Continue.class);
        assertThat(hook.wasCalled).isFalse();
    }

    @Test
    void dispatch_otherPhasesStopAfterFinalize() {
        SessionState state = SessionState.create("s1", "test");
        var hook1 = new TestPostModelHook(true, HookResult.stop(
                new StopReason(StopCategory.LOOP_DETECTED, "loop", 3)));
        var hook2 = new TestPostModelHook(true, HookResult.ok());

        StringBuilder callOrder = new StringBuilder();
        boolean[] finalized = {false};

        FireResult result = HookDispatcher.dispatch(
                List.of(hook1, hook2), state,
                (h, s) -> {
                    callOrder.append(h.name());
                    return h.afterModelCall(s, null);
                },
                reason -> new FireResult.Continue(),
                () -> finalized[0] = true
        );

        assertThat(callOrder.toString()).isEqualTo("hook1"); // hook2 skipped
        assertThat(finalized[0]).isTrue();
        assertThat(result).isInstanceOf(FireResult.Skip.class);
    }

    @Test
    void dispatch_noStop_returnsContinue() {
        SessionState state = SessionState.create("s1", "test");
        var hook = new TestPostModelHook(true, HookResult.ok());

        FireResult result = HookDispatcher.dispatch(
                List.of(hook), state,
                (h, s) -> h.afterModelCall(s, null),
                reason -> new FireResult.Continue(),
                () -> {}
        );

        assertThat(result).isInstanceOf(FireResult.Continue.class);
    }

    @Test
    void dispatch_exitStopsAndReturnsExit() {
        SessionState state = SessionState.create("s1", "test");
        var hook = new TestPostModelHook(true, HookResult.stop(
                new StopReason(StopCategory.GATE_REJECTED, "gate", 0)));

        FireResult result = HookDispatcher.dispatch(
                List.of(hook), state,
                (h, s) -> h.afterModelCall(s, null),
                reason -> new FireResult.Exit("terminated by gate"),
                () -> {}
        );

        assertThat(result).isInstanceOf(FireResult.Exit.class);
        assertThat(((FireResult.Exit) result).output()).isEqualTo("terminated by gate");
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
        public boolean isActive(SessionState state) { return active; }

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
        public boolean isActive(SessionState state) { return active; }

        @Override
        public HookResult afterModelCall(SessionState state, cn.kong.eon.llm.LlmResponse response) {
            wasCalled = true;
            return result;
        }
    }
}
