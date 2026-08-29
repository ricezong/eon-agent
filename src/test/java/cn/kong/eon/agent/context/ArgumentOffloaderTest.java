package cn.kong.eon.agent.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ArgumentOffloader} 单元测试。
 * <p>
 * 这里钉死本次线上故障的根因：<b>卸载后的 arguments 必须是严格合法的 JSON</b>。
 * 它会作为历史工具调用的参数原样回传给模型，供应商会校验格式，
 * 不合法会直接拒收整个请求（{@code 400 Invalid request parameters}）。
 * <p>
 * 故障形态：旧实现在 JSON 后面追加 {@code /* 参数已卸载… *\/} 注释，
 * 而 JSON 标准不支持注释；在站入口更严重，拼接的是
 * {@code {path: "x"}}（键名无引号）加注释，双重非法。
 */
class ArgumentOffloaderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static String writeArgs(int contentChars) {
        return "{\"file_path\":\"out/report.html\",\"contents\":\"" + "X".repeat(contentChars) + "\"}";
    }

    // ═══════════════════ 输出格式（故障回归） ═══════════════════

    /** 核心回归：整体必须可被 JSON 解析，不允许任何 JSON 之外的内容 */
    @Test
    void output_isStrictlyValidJson() {
        String result = ArgumentOffloader.offload(writeArgs(3000), "out/report.html", mapper);

        assertThat(result).isNotNull();
        assertThatCode(() -> mapper.readValue(result, Map.class))
                .as("卸载结果必须整体可被 JSON 解析，不得带注释")
                .doesNotThrowAnyException();
    }

    /** 输出必须以 { 开头、} 结尾 —— 直接排除"JSON 后面追加注释"的形态 */
    @Test
    void output_hasNoTrailingContent() {
        String result = ArgumentOffloader.offload(writeArgs(3000), "out/report.html", mapper);

        assertThat(result).startsWith("{").endsWith("}");
        assertThat(result).doesNotContain("/*");
        assertThat(result).doesNotContain("*/");
    }

    /** 说明文字只能出现在字符串值内部，不能出现在 JSON 结构层 */
    @Test
    void note_livesInsideStringValue_notInStructure() throws Exception {
        String result = ArgumentOffloader.offload(writeArgs(3000), "out/report.html", mapper);

        Map<String, Object> parsed = mapper.readValue(result, new TypeReference<>() {
        });

        assertThat(parsed.keySet()).containsExactly("file_path", "contents");
        assertThat(parsed.get("file_path")).isEqualTo("out/report.html");
        assertThat((String) parsed.get("contents"))
                .contains("3000 字符已卸载")
                .contains("out/report.html")
                .contains("read_file");
    }

    /** 含引号、换行的原内容不能破坏 JSON 结构 */
    @Test
    void output_staysValidJson_whenContentContainsEscapableChars() {
        String tricky = "{\"file_path\":\"a.html\",\"contents\":\"say \\\"hi\\\"\\n"
                + "Z".repeat(2500) + "\"}";

        String result = ArgumentOffloader.offload(tricky, "a.html", mapper);

        assertThat(result).isNotNull();
        assertThatCode(() -> mapper.readValue(result, Map.class)).doesNotThrowAnyException();
    }

    // ═══════════════════ 放弃卸载的条件 ═══════════════════

    @Test
    void returnsNull_whenArgsAreNotJson() {
        assertThat(ArgumentOffloader.offload("not json at all", "p.txt", mapper)).isNull();
    }

    @Test
    void returnsNull_whenArgsAreEmpty() {
        assertThat(ArgumentOffloader.offload("{}", "p.txt", mapper)).isNull();
        assertThat(ArgumentOffloader.offload(null, "p.txt", mapper)).isNull();
        assertThat(ArgumentOffloader.offload("  ", "p.txt", mapper)).isNull();
    }

    /** 没有单个超长字符串字段时不做替换 —— 替换只会丢信息，不会省空间 */
    @Test
    void returnsNull_whenNoLongStringField() {
        String args = "{\"a\":\"" + "x".repeat(150) + "\",\"b\":\"" + "y".repeat(150) + "\"}";

        assertThat(ArgumentOffloader.offload(args, "p.txt", mapper)).isNull();
    }

    /** 长内容藏在嵌套对象里时保守放弃：只处理顶层字符串字段 */
    @Test
    void returnsNull_whenLongContentIsNested() {
        String args = "{\"data\":{\"content\":\"" + "x".repeat(500) + "\"}}";

        assertThat(ArgumentOffloader.offload(args, "p.txt", mapper)).isNull();
    }

    @Test
    void returnsNull_whenMapperIsNull() {
        assertThat(ArgumentOffloader.offload(writeArgs(3000), "p.txt", null)).isNull();
    }

    // ═══════════════════ 路径提取 ═══════════════════

    @Test
    void extractPath_recognizesCommonKeyNames() {
        assertThat(ArgumentOffloader.extractPath("{\"file_path\":\"a.html\"}", mapper)).isEqualTo("a.html");
        assertThat(ArgumentOffloader.extractPath("{\"path\":\"b.html\"}", mapper)).isEqualTo("b.html");
        assertThat(ArgumentOffloader.extractPath("{\"output_path\":\"c.html\"}", mapper)).isEqualTo("c.html");
        assertThat(ArgumentOffloader.extractPath("{\"file\":\"d.html\"}", mapper)).isEqualTo("d.html");
    }

    @Test
    void extractPath_returnsNull_whenAbsentOrNotJson() {
        assertThat(ArgumentOffloader.extractPath("{\"contents\":\"zzz\"}", mapper)).isNull();
        assertThat(ArgumentOffloader.extractPath("not json", mapper)).isNull();
        assertThat(ArgumentOffloader.extractPath(null, mapper)).isNull();
    }

    /** 路径未知时说明降级为无路径版本，但输出仍是合法 JSON */
    @Test
    void output_staysValidJson_whenPathIsUnknown() {
        String args = "{\"contents\":\"" + "X".repeat(500) + "\"}";

        String result = ArgumentOffloader.offload(args, null, mapper);

        assertThat(result).isNotNull();
        assertThat(result).contains("内容已落盘");
        assertThatCode(() -> mapper.readValue(result, Map.class)).doesNotThrowAnyException();
    }
}
