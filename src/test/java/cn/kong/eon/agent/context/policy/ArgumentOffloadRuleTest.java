package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.ToolSupport;
import cn.kong.eon.agent.context.block.BlockProjector;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.model.CompressionState;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 工具参数无损卸载测试（在站版本）。
 * <p>
 * 规则从入站管线移到在站策略后，受保护区约束：
 * 只有 {@code turn < cutoffTurn} 的块才参与卸载。
 * <p>
 * 三条安全边界逐条钉死，任何一条失守都会造成<b>不可恢复的信息丢失</b>：
 * <ol>
 *   <li>只有声明了 {@code persistsArguments()} 的工具才卸载（磁盘上确实另有完整副本）</li>
 *   <li>只有执行<b>成功</b>的调用才卸载（失败的调用没真正落盘）</li>
 *   <li>只卸载超过阈值的参数（小参数替换后反而更大）</li>
 * </ol>
 * 另有一条<b>会让请求直接失败</b>的约束：卸载后的文本必须是严格合法的 JSON。
 */
class ArgumentOffloadRuleTest {

    private static final int OFFLOAD_MIN_CHARS = 1000;
    private static final double WATER_THRESHOLD = 0.65;
    private static final int SUMMARIZE_TURNS = 7;
    private static final long CONTEXT_MAX = 100_000L;
    private static final int TAIL_GUARD = 3;

    private final ObjectMapper mapper = new ObjectMapper();

    /** write_file 会把参数完整落盘；read_file 不会 */
    private static final ToolSupport TOOL_SUPPORT = new ToolSupport() {
        @Override
        public boolean persistsArguments(String toolName) {
            return "write_file".equals(toolName);
        }

        @Override
        public String summarizeArgs(String toolName, String argumentsJson) {
            return "{\"tool\": \"" + toolName + "\"}";
        }
    };

    private ArgumentOffloadRule rule() {
        return new ArgumentOffloadRule(WATER_THRESHOLD, SUMMARIZE_TURNS,
                OFFLOAD_MIN_CHARS, TOOL_SUPPORT, mapper);
    }

    /** 构造一个含 TOOL_ARGS 块的窗口，块轮次为 turn */
    private ContextWindow windowWithArgs(String toolName, String argsJson,
                                         boolean success, int turn) {
        AiMessage msg = AiMessage.from("writing",
                List.of(ToolExecutionRequest.builder()
                        .id("call_1").name(toolName).arguments(argsJson).build()));
        List<ContextBlock> blocks = BlockProjector.explode(msg, "g1", turn, TOOL_SUPPORT);
        // 模拟入站 ToolResultFormatRule 的效果：设置 success 标记
        if (success) {
            for (ContextBlock b : blocks) {
                if (b.kind() == cn.kong.eon.agent.context.block.BlockKind.TOOL_ARGS) {
                    b.setSuccess(true);
                }
            }
        }
        ContextWindow window = new ContextWindow();
        window.addAll(blocks);
        return window;
    }

    private ContextMetrics atWater(double waterLevel) {
        return new ContextMetrics(
                (long) (waterLevel * CONTEXT_MAX), 0, 0, 0,
                CONTEXT_MAX, 0, 0, Map.of());
    }

    private PolicyResult run(ContextWindow window, double water) {
        int latest = window.latestTurn();
        int cutoff = latest - TAIL_GUARD;
        RuleContext ctx = new RuleContext(window, atWater(water),
                new CompressionState(), cutoff, latest);
        ArgumentOffloadRule r = rule();
        if (!r.shouldFire(atWater(water), 0)) return PolicyResult.none();
        return r.apply(ctx);
    }

    private static String bigArgs() {
        return "{\"file_path\":\"out/report.html\",\"content\":\"" + "X".repeat(3000) + "\"}";
    }

    // ═══════════════════ 主路径 ═══════════════════

    @Test
    void offloadsLargeArgs_whenToolPersistsAndCallSucceeded() {
        // turn=0, latest=0, cutoff=-3 → turn < cutoff 为 false（保护区）
        // 需要用 turn=5, latest=5, cutoff=2 → turn=0 < 2 满足
        ContextWindow window = windowWithArgs("write_file", bigArgs(), true, 0);
        // 制造足够的轮次让 cutoff > 0
        window.addAll(BlockProjector.explode(
                AiMessage.from("filler"), "g2", 5, TOOL_SUPPORT));

        run(window, 0.8);

        ContextBlock block = window.blocks().stream()
                .filter(b -> b.kind() == cn.kong.eon.agent.context.block.BlockKind.TOOL_ARGS)
                .findFirst().orElseThrow();

        assertThat(block.isOffloaded()).isTrue();
        assertThat(block.chars()).isLessThan(300);
        assertThat(block.text()).doesNotContain("X".repeat(3000));
        assertThat(block.text()).contains("out/report.html");
        assertThat(block.text()).contains("已卸载");
    }

    /** 保持 JSON 外壳，模型仍能看出"这一步写了哪个文件、写了多大" */
    @Test
    void keepsJsonShell_replacingOnlyLongStringFields() throws Exception {
        ContextWindow window = windowWithArgs("write_file", bigArgs(), true, 0);
        window.addAll(BlockProjector.explode(
                AiMessage.from("filler"), "g2", 5, TOOL_SUPPORT));

        run(window, 0.8);

        ContextBlock block = window.blocks().stream()
                .filter(b -> b.kind() == cn.kong.eon.agent.context.block.BlockKind.TOOL_ARGS)
                .findFirst().orElseThrow();

        var parsed = mapper.readValue(block.text(), Map.class);

        assertThat(parsed).containsKey("file_path");
        assertThat(parsed.get("file_path")).isEqualTo("out/report.html");
        assertThat((String) parsed.get("content")).contains("3000 字符已卸载");
    }

    /**
     * 回归：卸载后的文本会作为历史工具调用的 {@code arguments} 原样回传给模型，
     * 供应商会校验该字段格式，不是严格合法 JSON 会被直接拒收（400）。
     */
    @Test
    void offloadedArgs_isStrictlyValidJson() {
        ContextWindow window = windowWithArgs("write_file", bigArgs(), true, 0);
        window.addAll(BlockProjector.explode(
                AiMessage.from("filler"), "g2", 5, TOOL_SUPPORT));

        run(window, 0.8);

        ContextBlock block = window.blocks().stream()
                .filter(b -> b.kind() == cn.kong.eon.agent.context.block.BlockKind.TOOL_ARGS)
                .findFirst().orElseThrow();

        assertThat(block.isOffloaded()).isTrue();
        assertThatCode(() -> mapper.readValue(block.text(), Map.class))
                .as("卸载后的 arguments 必须整体可被 JSON 解析，不得带注释等 JSON 外内容")
                .doesNotThrowAnyException();
    }

    /** 不同参数形状（多字段、含转义）下都必须保持合法 JSON */
    @Test
    void offloadedArgs_staysValidJson_acrossShapes() {
        List<String> shapes = List.of(
                "{\"file_path\":\"a.html\",\"contents\":\"" + "X".repeat(2500) + "\"}",
                "{\"file_path\":\"b.html\",\"mode\":\"overwrite\",\"contents\":\"" + "Y".repeat(2500) + "\"}",
                "{\"file_path\":\"c.html\",\"contents\":\"line1\\nline2\\\"q\\\" " + "Z".repeat(2500) + "\"}");

        for (String args : shapes) {
            ContextWindow window = windowWithArgs("write_file", args, true, 0);
            window.addAll(BlockProjector.explode(
                    AiMessage.from("filler"), "g2", 5, TOOL_SUPPORT));

            run(window, 0.8);

            ContextBlock block = window.blocks().stream()
                    .filter(b -> b.kind() == cn.kong.eon.agent.context.block.BlockKind.TOOL_ARGS)
                    .findFirst().orElseThrow();

            assertThat(block.isOffloaded()).isTrue();
            assertThatCode(() -> mapper.readValue(block.text(), Map.class))
                    .as("参数形状 " + args.substring(0, 30) + " 卸载后必须是合法 JSON")
                    .doesNotThrowAnyException();
        }
    }

    /**
     * 参数不是合法 JSON 时<b>放弃卸载</b>而不是退化成文本摘要。
     */
    @Test
    void doesNotOffload_whenArgsAreNotJson() {
        String junk = "not json at all " + "y".repeat(3000);
        ContextWindow window = windowWithArgs("write_file", junk, true, 0);
        window.addAll(BlockProjector.explode(
                AiMessage.from("filler"), "g2", 5, TOOL_SUPPORT));

        run(window, 0.8);

        ContextBlock block = window.blocks().stream()
                .filter(b -> b.kind() == cn.kong.eon.agent.context.block.BlockKind.TOOL_ARGS)
                .findFirst().orElseThrow();

        assertThat(block.isOffloaded()).isFalse();
        assertThat(block.text()).isEqualTo(junk);
    }

    // ═══════════════════ 安全边界 ═══════════════════

    @Test
    void doesNotOffload_whenCallFailed() {
        ContextWindow window = windowWithArgs("write_file", bigArgs(), false, 0);
        window.addAll(BlockProjector.explode(
                AiMessage.from("filler"), "g2", 5, TOOL_SUPPORT));

        run(window, 0.8);

        ContextBlock block = window.blocks().stream()
                .filter(b -> b.kind() == cn.kong.eon.agent.context.block.BlockKind.TOOL_ARGS)
                .findFirst().orElseThrow();

        assertThat(block.isOffloaded()).isFalse();
        assertThat(block.text()).isEqualTo(bigArgs());
    }

    @Test
    void doesNotOffload_whenToolDoesNotPersistArguments() {
        ContextWindow window = windowWithArgs("read_file", bigArgs(), true, 0);
        window.addAll(BlockProjector.explode(
                AiMessage.from("filler"), "g2", 5, TOOL_SUPPORT));

        run(window, 0.8);

        ContextBlock block = window.blocks().stream()
                .filter(b -> b.kind() == cn.kong.eon.agent.context.block.BlockKind.TOOL_ARGS)
                .findFirst().orElseThrow();

        assertThat(block.isOffloaded()).isFalse();
        assertThat(block.text()).isEqualTo(bigArgs());
    }

    @Test
    void doesNotOffload_whenArgsBelowMinChars() {
        String smallArgs = "{\"file_path\":\"a.txt\",\"content\":\"short\"}";
        ContextWindow window = windowWithArgs("write_file", smallArgs, true, 0);
        window.addAll(BlockProjector.explode(
                AiMessage.from("filler"), "g2", 5, TOOL_SUPPORT));

        run(window, 0.8);

        ContextBlock block = window.blocks().stream()
                .filter(b -> b.kind() == cn.kong.eon.agent.context.block.BlockKind.TOOL_ARGS)
                .findFirst().orElseThrow();

        assertThat(block.isOffloaded()).isFalse();
        assertThat(block.text()).isEqualTo(smallArgs);
    }

    // ═══════════════════ 保护区约束（在站规则独有） ═══════════════════

    @Test
    void doesNotOffload_whenBlockIsInTailGuard() {
        // turn=5, latest=5, cutoff=2, turn=5 >= cutoff → 保护区
        ContextWindow window = windowWithArgs("write_file", bigArgs(), true, 5);

        run(window, 0.8);

        ContextBlock block = window.blocks().stream()
                .filter(b -> b.kind() == cn.kong.eon.agent.context.block.BlockKind.TOOL_ARGS)
                .findFirst().orElseThrow();

        // 在保护区内，不卸载
        assertThat(block.isOffloaded()).isFalse();
        assertThat(block.text()).isEqualTo(bigArgs());
    }

    @Test
    void doesNotFire_whenWaterLevelBelowThreshold() {
        ContextWindow window = windowWithArgs("write_file", bigArgs(), true, 0);
        window.addAll(BlockProjector.explode(
                AiMessage.from("filler"), "g2", 5, TOOL_SUPPORT));

        // 水位 0.3 < 0.65，轮数 0 < 7 → 不触发
        PolicyResult result = run(window, 0.3);

        assertThat(result.applied()).isFalse();

        ContextBlock block = window.blocks().stream()
                .filter(b -> b.kind() == cn.kong.eon.agent.context.block.BlockKind.TOOL_ARGS)
                .findFirst().orElseThrow();

        assertThat(block.isOffloaded()).isFalse();
    }
}
