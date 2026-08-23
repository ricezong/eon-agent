package cn.kong.eon;

import cn.kong.eon.tool.builtin.AskQuestionTool;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 启动类。
 * <p>
 * 同时保留 CLI 入口 {@code AgentBootstrap.main()} 兼容命令行模式。
 */
@SpringBootApplication
public class EonAgentApplication {

    public static void main(String[] args) {
        // API 模式：禁用 AskQuestionTool 的 stdin 阻塞
        AskQuestionTool.setApiMode(true);
        SpringApplication.run(EonAgentApplication.class, args);
    }
}
