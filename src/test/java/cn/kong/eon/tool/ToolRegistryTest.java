package cn.kong.eon.tool;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ToolDescriptor createSimpleDescriptor(String name, ToolPermission perm) {
        return new ToolDescriptor(
                name, "test tool: " + name, perm,
                ToolSpecification.builder().name(name).description("test").build(),
                (args, state, ctx) -> ToolOutcome.success("executed: " + name)
        );
    }

    @Test
    void register_addsToTools() {
        ToolRegistry registry = new ToolRegistry(Set.of(), mapper);
        registry.register(createSimpleDescriptor("read_file", ToolPermission.READONLY));

        assertThat(registry.contains("read_file")).isTrue();
        assertThat(registry.get("read_file")).isNotNull();
        assertThat(registry.getAll()).hasSize(1);
    }

    @Test
    void register_whitelistFiltersNonListed() {
        ToolRegistry registry = new ToolRegistry(Set.of("read_file"), mapper);
        registry.register(createSimpleDescriptor("read_file", ToolPermission.READONLY));
        registry.register(createSimpleDescriptor("write_file", ToolPermission.RESTRICTED_WRITE));

        assertThat(registry.contains("read_file")).isTrue();
        assertThat(registry.contains("write_file")).isFalse();
        assertThat(registry.getAll()).hasSize(1);
    }

    @Test
    void register_emptyWhitelistAllowsAll() {
        ToolRegistry registry = new ToolRegistry(Set.of(), mapper);
        registry.register(createSimpleDescriptor("tool_a", ToolPermission.READONLY));
        registry.register(createSimpleDescriptor("tool_b", ToolPermission.DESTRUCTIVE));

        assertThat(registry.getAll()).hasSize(2);
    }

    @Test
    void execute_localTool_returnsOutcome() {
        ToolRegistry registry = new ToolRegistry(Set.of(), mapper);
        registry.register(createSimpleDescriptor("read_file", ToolPermission.READONLY));

        SessionState state = SessionState.create("s1", "test");
        ToolOutcome result = registry.execute("read_file", Map.of(), state, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("executed: read_file");
    }

    @Test
    void execute_unknownTool_returnsFailure() {
        ToolRegistry registry = new ToolRegistry(Set.of(), mapper);
        SessionState state = SessionState.create("s1", "test");

        ToolOutcome result = registry.execute("nonexistent", Map.of(), state, null);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("Tool not found");
    }

    @Test
    void execute_throwingTool_returnsFailure() {
        ToolRegistry registry = new ToolRegistry(Set.of(), mapper);
        ToolDescriptor throwingTool = new ToolDescriptor(
                "bomb", "throws", ToolPermission.READONLY,
                ToolSpecification.builder().name("bomb").description("throws").build(),
                (args, state, ctx) -> { throw new RuntimeException("kaboom"); }
        );
        registry.register(throwingTool);

        SessionState state = SessionState.create("s1", "test");
        ToolOutcome result = registry.execute("bomb", Map.of(), state, null);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("kaboom");
    }

    @Test
    void getPermission_localTool() {
        ToolRegistry registry = new ToolRegistry(Set.of(), mapper);
        registry.register(createSimpleDescriptor("read_file", ToolPermission.READONLY));
        registry.register(createSimpleDescriptor("delete_file", ToolPermission.DESTRUCTIVE));

        assertThat(registry.getPermission("read_file")).isEqualTo(ToolPermission.READONLY);
        assertThat(registry.getPermission("delete_file")).isEqualTo(ToolPermission.DESTRUCTIVE);
    }

    @Test
    void getPermission_unknownTool_returnsNull() {
        ToolRegistry registry = new ToolRegistry(Set.of(), mapper);
        assertThat(registry.getPermission("ghost")).isNull();
    }

    @Test
    void isDestructive_correctlyClassifies() {
        ToolRegistry registry = new ToolRegistry(Set.of(), mapper);
        registry.register(createSimpleDescriptor("read_file", ToolPermission.READONLY));
        registry.register(createSimpleDescriptor("delete_file", ToolPermission.DESTRUCTIVE));

        assertThat(registry.isDestructive("delete_file")).isTrue();
        assertThat(registry.isDestructive("read_file")).isFalse();
    }

    @Test
    void getAllToolNames_returnsLocalAndMcp() {
        ToolRegistry registry = new ToolRegistry(Set.of(), mapper);
        registry.register(createSimpleDescriptor("local_tool", ToolPermission.READONLY));

        assertThat(registry.getAllToolNames()).contains("local_tool");
    }

    @Test
    void getSpecifications_returnsAllSpecs() {
        ToolRegistry registry = new ToolRegistry(Set.of(), mapper);
        registry.register(createSimpleDescriptor("tool_a", ToolPermission.READONLY));
        registry.register(createSimpleDescriptor("tool_b", ToolPermission.READONLY));

        assertThat(registry.getSpecifications()).hasSize(2);
    }

    @Test
    void closeAll_closesAllTools() {
        ToolRegistry registry = new ToolRegistry(Set.of(), mapper);
        boolean[] closed = {false};

        ToolDescriptor closeableTool = new ToolDescriptor(
                "closeable", "closable tool", ToolPermission.READONLY,
                ToolSpecification.builder().name("closeable").description("test").build(),
                new ToolExecutor() {
                    @Override
                    public ToolOutcome execute(Map<String, Object> args, SessionState state, ToolContext ctx) {
                        return ToolOutcome.success("ok");
                    }
                    @Override
                    public void close() {
                        closed[0] = true;
                    }
                }
        );
        registry.register(closeableTool);
        registry.closeAll();

        assertThat(closed[0]).isTrue();
    }
}
