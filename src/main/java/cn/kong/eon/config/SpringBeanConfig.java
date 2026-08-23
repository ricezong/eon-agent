package cn.kong.eon.config;

import cn.kong.eon.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局 Spring Bean 装配。
 * <p>
 * 装配跨会话共享的单例 Bean：{@link AgentConfig}、{@link LlmClient}。
 * 会话级资源（EonAgent、SessionState 等）由 {@code AgentBootstrapFactory} 按需创建。
 */
@Configuration
public class SpringBeanConfig {
    private static final Logger log = LoggerFactory.getLogger(SpringBeanConfig.class);

    @Bean
    public AgentConfig agentConfig() {
        AgentConfig config = AgentConfig.loadFromClasspath("config/agent.yaml");
        log.info("AgentConfig loaded: llm={}/{}, loop.maxSteps={}",
                config.getLlm().provider, config.getLlm().modelName, config.getLoop().maxSteps);
        return config;
    }

    @Bean
    public LlmClient llmClient(AgentConfig config) {
        return new LlmClient(config);
    }
}
