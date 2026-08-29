package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.ToolSupport;
import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 工具参数无损卸载测试——本次改造收益最大的一条规则。
 * <p>
 * 三条安全边界逐条钉死，任何一条失守都会造成<b>不可恢复的信息丢失</b>：
 * <ol>
 *   <li>只有声明了 {@code persistsArguments()} 的工具才卸载（磁盘上确实另有完整副本）</li>
 *   <li>只有执行<b>成功</b>的调用才卸载（失败的调用没真正落盘）</li>
 *   <li>只卸载超过阈值的参数（小参数替换后反而更大）</li>
 * </ol>
 * 另有一条<b>会让请求直接失败</b>的约束：卸载后的文本必须是严格合法的 JSON
 * （见 {@link #offloadedArgs_isStrictlyValidJson()}）。
 */
class ArgumentOffloadRuleTest {

    private static final int OFFLOAD_MIN_CHARS = 1000;
    private static final String CALL_ID = "call_1";

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

    private ContextPipeline pipeline() {
        return new ContextPipeline(
                List.of(new ArgumentOffloadRule()),
                TOOL_SUPPORT, null, TOOL_SUPPORT, mapper, 2000, OFFLOAD_MIN_CHARS);
    }

    private ContextBlock ingestArgs(String toolName, String argsJson, boolean succeeded) {
        AiMessage msg = AiMessage.from("writing",
                List.of(ToolExecutionRequest.builder()
                        .id(CALL_ID).name(toolName).arguments(argsJson).build()));
        return pipeline().ingest(msg, 3, succeeded ? Set.of(CALL_ID) : Set.of())
                .stream()
                .filter(b -> b.kind() == BlockKind.TOOL_ARGS)
                .findFirst()
                .orElseThrow();
    }

    private static String bigArgs() {
        return "{\"file_path\":\"out/report.html\",\"content\":\"" + "X".repeat(3000) + "\"}";
    }

    // ═══════════════════ 主路径 ═══════════════════

    @Test
    void offloadsLargeArgs_whenToolPersistsAndCallSucceeded() {
        ContextBlock block = ingestArgs("write_file", bigArgs(), true);

        assertThat(block.isOffloaded()).isTrue();
        assertThat(block.chars()).isLessThan(300);
        assertThat(block.text()).doesNotContain("X".repeat(3000));
        assertThat(block.text()).contains("out/report.html");
        assertThat(block.text()).contains("已卸载");
    }

    /** 保持 JSON 外壳，模型仍能看出"这一步写了哪个文件、写了多大" */
    @Test
    void keepsJsonShell_replacingOnlyLongStringFields() throws Exception {
        ContextBlock block = ingestArgs("write_file", bigArgs(), true);

        var parsed = mapper.readValue(block.text(), Map.class);

        assertThat(parsed).containsKey("file_path");
        assertThat(parsed.get("file_path")).isEqualTo("out/report.html");
        assertThat((String) parsed.get("content")).contains("3000 字符已卸载");
    }

    /**
     * 回归：卸载后的文本会作为历史工具调用的 {@code arguments} 原样回传给模型，
     * 供应商会校验该字段格式，不是严格合法 JSON 会被直接拒收（400）。
     * <p>
     * 这里<b>不能</b>先切掉任何前缀或后缀再解析——旧实现在 JSON 后面追加了
     * {@code /* 注释 *\/}，测试因为先切注释而没能拦住它。
     */
    @Test
    void offloadedArgs_isStrictlyValidJson() {
        ContextBlock block = ingestArgs("write_file", bigArgs(), true);

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
            ContextBlock block = ingestArgs("write_file", args, true);
            assertThat(block.isOffloaded()).isTrue();
            assertThatCode(() -> mapper.readValue(block.text(), Map.class))
                    .as("参数形状 " + args.substring(0, 30) + " 卸载后必须是合法 JSON")
                    .doesNotThrowAnyException();
        }
    }

    /**
     * 参数不是合法 JSON 时<b>放弃卸载</b>而不是退化成文本摘要。
     * 退化分支曾产出 {@code {path: "x"} /* 注释 *\/}，既非标准 JSON 又带注释。
     */
    @Test
    void doesNotOffload_whenArgsAreNotJson() {
        String junk = "not json at all " + "y".repeat(3000);
        ContextBlock block = ingestArgs("write_file", junk, true);

        assertThat(block.isOffloaded()).isFalse();
        assertThat(block.text()).isEqualTo(junk);
    }

    // ═══════════════════ 安全边界 ═══════════════════

    @Test
    void doesNotOffload_whenCallFailed() {
        ContextBlock block = ingestArgs("write_file", bigArgs(), false);

        assertThat(block.isOffloaded()).isFalse();
        assertThat(block.text()).isEqualTo(bigArgs());
    }

    @Test
    void doesNotOffload_whenToolDoesNotPersistArguments() {
        ContextBlock block = ingestArgs("read_file", bigArgs(), true);

        assertThat(block.isOffloaded()).isFalse();
        assertThat(block.text()).isEqualTo(bigArgs());
    }

    @Test
    void doesNotOffload_whenArgsBelowMinChars() {
        String smallArgs = "{\"file_path\":\"a.txt\",\"content\":\"short\"}";
        ContextBlock block = ingestArgs("write_file", smallArgs, true);

        assertThat(block.isOffloaded()).isFalse();
        assertThat(block.text()).isEqualTo(smallArgs);
    }

    @Test
    void doesNotTouchAiTextOrUserInput() {
        dev.langchain4j.data.message.UserMessage user =
                dev.langchain4j.data.message.UserMessage.from("原始用户输入，必须逐字保留");

        List<ContextBlock> blocks = pipeline().ingest(user, 1, Set.of());

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).text()).isEqualTo("原始用户输入，必须逐字保留");
    }
}
