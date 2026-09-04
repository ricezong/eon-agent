package cn.kong.eon.tool;

import cn.kong.eon.model.ToolPermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具注册行为测试。权限以工具自身注解声明为准，配置层不再覆盖。
 */
class ToolRegistryTest {

    private static ToolDescriptor descriptor(String name, ToolPermission permission) {
        ToolSpecification spec = ToolSpecification.builder()
                .name(name)
                .description("test tool " + name)
                .build();
        return new ToolDescriptor(name, "desc " + name, permission, spec,
                (args, state, ctx) -> ToolOutcome.success("ok"));
    }

    private static ToolRegistry registry(Set<String> whitelist) {
        return new ToolRegistry(whitelist, new ObjectMapper());
    }

    @Test
    void whitelistedToolIsRegistered() {
        ToolRegistry registry = registry(Set.of("write"));

        registry.register(descriptor("write", ToolPermission.RESTRICTED_WRITE));

        assertThat(registry.contains("write")).isTrue();
        assertThat(registry.getPermission("write")).isEqualTo(ToolPermission.RESTRICTED_WRITE);
    }

    @Test
    void toolOutsideWhitelistIsNotRegistered() {
        ToolRegistry registry = registry(Set.of("read_file"));

        registry.register(descriptor("write", ToolPermission.RESTRICTED_WRITE));

        assertThat(registry.contains("write")).isFalse();
    }

    @Test
    void annotatedPermissionIsPreserved() {
        ToolRegistry registry = registry(Set.of("write"));

        registry.register(descriptor("write", ToolPermission.DESTRUCTIVE));

        assertThat(registry.getPermission("write")).isEqualTo(ToolPermission.DESTRUCTIVE);
        assertThat(registry.isDestructive("write")).isTrue();
    }

    @Test
    void emptyWhitelistAllowsAll() {
        ToolRegistry registry = registry(Set.of());

        registry.register(descriptor("write", ToolPermission.RESTRICTED_WRITE));

        // 空白名单不构成限制
        assertThat(registry.contains("write")).isTrue();
    }
}
