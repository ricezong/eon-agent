package cn.kong.eon.agent.hook.postmodel;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 工具存在性校验（PostModel, order=10）。
 * 模型输出的工具调用中如果包含不存在的工具，注入 nudge 提示。
 */
public class ToolValidationHook implements Hook.PostModelHook {
    private static final Logger log = LoggerFactory.getLogger(ToolValidationHook.class);

    private static final String TOOL_NOT_FOUND_NUDGE = "工具 %s 不存在，请使用可用工具。";

    private final ToolRegistry toolRegistry;

    public ToolValidationHook(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String name() {
        return "ToolValidation";
    }

    @Override
    public boolean active(SessionState state) {
        return true;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public HookResult afterModelCall(SessionState state) {
        List<ToolExecutionRequest> requests = state.getPendingToolCalls();
        if (requests == null || requests.isEmpty()) {
            return HookResult.ok();
        }

        for (ToolExecutionRequest req : requests) {
            if (!toolRegistry.contains(req.name())) {
                state.addNudge(String.format(TOOL_NOT_FOUND_NUDGE, req.name()));
                log.warn("[ToolValidation] 工具 '{}' 不存在", req.name());
                return HookResult.skip();
            }
        }
        return HookResult.ok();
    }
}
