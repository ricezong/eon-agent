package cn.kong.eon.runtime;

import cn.kong.eon.engine.guard.ToolCircuitBreaker;
import cn.kong.eon.engine.hook.Hook;
import cn.kong.eon.engine.stop.StopHandler;
import cn.kong.eon.engine.exec.ToolCallDispatcher;
import cn.kong.eon.engine.exec.TurnMessageWriter;
import cn.kong.eon.event.AgentEventListener;
import cn.kong.eon.store.ledger.TranscriptLedger;
import cn.kong.eon.tool.ToolContext;

import java.util.List;

/**
 * 会话上下文。打包一次会话运行所需的全部会话级依赖，传递给无状态引擎 {@code EonAgent.run}。
 * <p>
 * 引擎只持有应用级依赖（config/llm/toolService/prompt），会话级组件通过本对象在每次 {@code run} 时注入。
 * 每次会话开始时由 {@link AgentSession} 组装，运行结束后随会话一起销毁。
 *
 * @param sessionState      会话运行时状态
 * @param transcriptLedger  对话账本
 * @param toolContext       工具运行时依赖
 * @param circuitBreaker    工具熔断器
 * @param listeners         事件监听器列表（SSE + 日志）
 * @param hooks             按阶段分组的 Hook 列表
 * @param toolDispatcher    工具执行调度器
 * @param messageWriter     消息回填器
 * @param stopHandler       停止处理器
 */
public record SessionContext(
        SessionState sessionState,
        TranscriptLedger transcriptLedger,
        ToolContext toolContext,
        ToolCircuitBreaker circuitBreaker,
        List<AgentEventListener> listeners,
        List<Hook> hooks,
        ToolCallDispatcher toolDispatcher,
        TurnMessageWriter messageWriter,
        StopHandler stopHandler
) {
}
