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
 * 斗破苍穹下载场景测试。
 *
 * 注意：此测试需要 LLM_API_KEY 环境变量。
 * 工具为真实实现（web_search/web_read/download 真实调用网络）。
 * 若无 API Key，测试将跳过。
 */
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
class DemoScenarioTest {
    private static final Logger log = LoggerFactory.getLogger(DemoScenarioTest.class);

    @Test
    void should_download_novel_within_5_turns() {
        // 1. 加载配置
        AgentConfig config = AgentConfig.loadFromClasspath("config/agent.yaml");

        // 2. 构建任务
        String userInput = "请下载《斗破苍穹》小说 txt 文件到本地";
        String sessionId = "test_dpcq_" + System.currentTimeMillis();
        SessionState state = SessionState.create(sessionId, userInput);

        // 3. 构建 Agent
        EonAgent agent = AgentBootstrap.build(config, sessionId);

        // 4. 执行
        String result = agent.run(state);

        // 5. 验收
        log.info("=== 验收 ===");
        log.info("Turn count: {}", state.getTurnCount());
        log.info("Finished: {}", state.isFinished());

        // 验收标准 1: 5 轮内完成
        assertThat(state.getTurnCount()).isLessThanOrEqualTo(5);

        // 验收标准 2: 任务正常结束
        assertThat(state.isFinished()).isTrue();

        log.info("=== 测试通过 ===");
    }
}
