package cn.kong.eon;

import cn.kong.eon.config.AgentConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Spring Boot 启动类。
 */
@SpringBootApplication
@EnableConfigurationProperties(AgentConfig.class)
public class EonAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(EonAgentApplication.class, args);
    }
}
