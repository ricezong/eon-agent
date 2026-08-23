package cn.kong.eon.bootstrap;

import cn.kong.eon.agent.EonAgent;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.session.AgentBootstrapFactory;
import cn.kong.eon.session.AgentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;

/**
 * CLI 启动器（兼容旧模式）。
 * <p>
 * 委托 {@link AgentBootstrapFactory} 完成 Agent 组装，消除重复代码。
 * 保留 JVM shutdown hook 用于 CLI 模式下关闭 MCP 连接。
 */
public class AgentBootstrap {
    private static final Logger log = LoggerFactory.getLogger(AgentBootstrap.class);

    /**
     * 构建 EonAgent 实例。
     * <p>
     * 委托 {@link AgentBootstrapFactory#createSession} 完成组装，
     * 从返回的 {@link AgentSession} 中取出 {@link EonAgent}。
     * 同时注册 JVM shutdown hook 关闭 MCP 连接。
     *
     * @param config    Agent 配置
     * @param sessionId 会话 ID
     * @return EonAgent 实例
     */
    public static EonAgent build(AgentConfig config, String sessionId) {
        LlmClient llmClient = new LlmClient(config);
        AgentBootstrapFactory factory = new AgentBootstrapFactory(config, llmClient);

        // 使用空输入创建会话（CLI 模式下实际输入在调用 agent.run() 时通过 SessionState 传入）
        AgentSession session = factory.createSession(sessionId, "");

        // CLI 模式注册 JVM shutdown hook 关闭 MCP 连接
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            session.destroy();
        }, "Agent-ShutdownHook"));

        log.info("AgentBootstrap (CLI) delegated to AgentBootstrapFactory, session={}", sessionId);
        return session.getAgent();
    }

    /** 交互式 CLI 启动入口。 */
    public static void main(String[] args) {
        AgentConfig config = AgentConfig.loadFromClasspath("config/agent.yaml");

        System.out.println("========================================");
        System.out.println("  Eon Agent v2.0");
        System.out.println("  LLM: " + config.getLlm().provider + " / " + config.getLlm().modelName);
        System.out.println("  MCP servers: " + config.getMcp().servers.keySet());
        System.out.println("========================================");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        System.out.print("请输入问题或任务: ");
        String userInput = scanner.nextLine().trim();

        if (userInput.isEmpty()) {
            System.out.println("输入为空，退出。");
            return;
        }

        String sessionId = "session_" + System.currentTimeMillis();
        SessionState state = SessionState.create(sessionId, userInput);

        EonAgent agent = build(config, sessionId);
        String result = agent.run(state);

        System.out.println();
        System.out.println("========================================");
        System.out.println("  结束");
        System.out.println("========================================");
        System.out.println(result);
    }
}
