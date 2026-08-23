package cn.kong.eon;

import cn.kong.eon.agent.EonAgent;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.bootstrap.AgentBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 搜索场景测试。
 *
 * 注意：此测试需要 LLM_API_KEY 环境变量。
 * 若无 API Key，测试将跳过。
 * P1 阶段：终止机制改为"无工具调用即退出"，不再使用 isFinished()。
 */
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
class DemoScenarioTest {
    private static final Logger log = LoggerFactory.getLogger(DemoScenarioTest.class);

    @Test
    void should_complete_search_task() {
        AgentConfig config = AgentConfig.loadFromClasspath("config/agent.yaml");

        String userInput = "请搜索最近的 AI 技术进展并总结";
        String sessionId = "test_search_" + System.currentTimeMillis();
        SessionState state = SessionState.create(sessionId, userInput);

        EonAgent agent = AgentBootstrap.build(config, sessionId);

        String result = agent.run(state);

        log.info("=== 验收 ===");
        log.info("Turn count: {}", state.getTurnCount());

        assertThat(state.getTurnCount()).isGreaterThan(0);
        assertThat(result).isNotBlank();

        log.info("=== 测试通过 ===");
    }
}
