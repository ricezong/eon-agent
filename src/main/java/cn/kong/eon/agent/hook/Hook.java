package cn.kong.eon.agent.hook;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.model.SessionState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;

/**
 * Hook 基础接口。每个 Hook 只属于一个执行阶段，通过 order 控制阶段内顺序。
 * 引擎按执行阶段分组调度：PreModel → PostModel → PreTool → PostTool
 */
public interface Hook {

    /** 模块名称（用于日志）。 */
    String name();

    /** 是否激活。 */
    boolean isActive(SessionState state);

    /** 阶段内执行顺序，数值小先执行。默认 100。 */
    default int order() { return 100; }

    /** 阶段 1：模型调用前。组装上下文、预算检查、压缩、渲染。 */
    interface PreModelHook extends Hook {
        HookResult beforeModelCall(SessionState state, ContextBuilder ctx);
    }

    /** 阶段 2：模型调用后。解析输出、循环检测。 */
    interface PostModelHook extends Hook {
        HookResult afterModelCall(SessionState state, LlmResponse response);
    }

    /** 阶段 3：工具执行前。门禁校验。 */
    interface PreToolHook extends Hook {
        HookResult beforeToolExecution(SessionState state, List<ToolExecutionRequest> requests);
    }

    /** 阶段 4：工具执行后。状态更新、快照保存、熔断检测。 */
    interface PostToolHook extends Hook {
        HookResult afterToolExecution(SessionState state, String toolName, boolean success);
    }
}
