package cn.kong.eon.engine.hook;

import cn.kong.eon.runtime.RunContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;

/**
 * Hook 基础接口。每个 Hook 只属于一个执行阶段，通过 order 控制阶段内顺序。
 * 引擎按阶段分组调度：PreModel → PostModel → PreTool → PostTool。
 * <p>
 * <b>全部方法只接受 {@link RunContext}</b>：会话级数据从 {@code r.session()} 取、
 * 任务级从 {@code r.task()} 取、轮次级从 {@code r.turn()} 取。
 * Hook 因此不含任何可变状态，可安全作为应用级单例（{@code @Component}）被多会话共享。
 * 需要跨轮累计的状态一律外置（如 {@code LoopDetector}、{@code ProgressTracker} 挂在 TaskScope 上）。
 */
public interface Hook {

    /** 模块名称（用于日志）。 */
    String name();

    /** 是否激活。默认激活，仅少数 Hook 需要按配置关闭。 */
    default boolean active(RunContext r) {
        return true;
    }

    /** 阶段内执行顺序，数值小先执行。默认 100。 */
    default int order() {
        return 100;
    }

    /** 阶段 1：模型调用前。上下文从 {@code r.turn().prompt()} 取。 */
    interface PreModelHook extends Hook {
        HookResult beforeModelCall(RunContext r);
    }

    /** 阶段 2：模型调用后。解析输出、循环检测。 */
    interface PostModelHook extends Hook {
        HookResult afterModelCall(RunContext r);
    }

    /** 阶段 3：工具执行前。门禁校验。 */
    interface PreToolHook extends Hook {
        HookResult beforeToolExecution(RunContext r, List<ToolExecutionRequest> requests);
    }

    /** 阶段 4：工具执行后。状态更新、快照保存、熔断检测。 */
    interface PostToolHook extends Hook {
        HookResult afterToolExecution(RunContext r, String toolName, boolean success);
    }
}
