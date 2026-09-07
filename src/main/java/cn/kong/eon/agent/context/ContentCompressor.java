package cn.kong.eon.agent.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内容压缩。把一段文本变短的手法都收在这里，按内容形态分两种：
 * <ul>
 *   <li>{@link #headTail}——面向普通文本。保留头尾、中段省略，不保证产出任何格式。</li>
 *   <li>{@link #skeleton}——面向工具参数 JSON。裁剪长字段、保留结构，<b>产出始终是合法 JSON</b>。</li>
 * </ul>
 * <p>
 * <b>选错手法会直接让请求失败</b>：工具参数会原样回传给模型并接受格式校验，
 * 一旦被 headTail 截断，字符串中间就会插入裸换行符，整个请求被拒收（400）。
 * 判定标准只有一条——这段文本有没有"必须保持的格式"：有就用 skeleton，没有就用 headTail。
 */
public final class ContentCompressor {

    /** 参数中超过该长度的字符串字段才被视为大字段并替换。 */
    private static final int LONG_FIELD_CHARS = 200;

    private final ObjectMapper objectMapper;

    public ContentCompressor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ═══════════════ 普通文本 ═══════════════

    /**
     * 头尾保留截断：保留开头与结尾，中段以省略号替代，不带任何文字说明。
     * 只适用于没有格式约束的纯文本——工具结果、模型正文、用户粘贴的文件。
     *
     * @param keepChars 保留的总字符数，头尾各半
     * @return 截断后的文本；原文未超过保留长度时原样返回
     */
    public String headTail(String content, int keepChars) {
        if (content == null) {
            return "";
        }
        int headChars = keepChars / 2;
        int tailChars = keepChars - headChars;
        if (content.length() <= headChars + tailChars) {
            return content;
        }
        return content.substring(0, headChars)
                + "\n...\n"
                + content.substring(content.length() - tailChars);
    }

    // ═══════════════ 工具参数 JSON ═══════════════

    /**
     * 参数骨架化：把 JSON 中过长的字符串字段替换为一行"已裁剪"的说明，
     * 其余字段与整体结构原样保留。凡是要缩减工具参数，只能用它，不能用
     * {@link #headTail}——截断产出的片段不是合法 JSON。
     * <p>
     * 这是一次<b>有损裁剪</b>而非卸载：被裁掉的字段不再有副本。
     * 全项目只有 write 工具会产生大参数，而 write 本身已把内容写进目标文件，
     * path 等短字段保留在骨架里，模型据此即可取回原文。
     *
     * @param json 原始参数 JSON
     * @return 骨架 JSON；入参不是 JSON、不含大字段或序列化失败时返回 {@code null}，
     *         调用方必须据此放弃替换——宁可多占 token，也不能发出会被拒收的请求
     */
    public String skeleton(String json) {
        Map<String, Object> args = parse(json);
        if (args.isEmpty()) {
            return null;
        }

        Map<String, Object> slim = new LinkedHashMap<>();
        boolean replacedAny = false;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s && s.length() > LONG_FIELD_CHARS) {
                slim.put(entry.getKey(), "<" + s.length() + " 字符已裁剪，原文不在上下文中>");
                replacedAny = true;
            } else {
                slim.put(entry.getKey(), value);
            }
        }
        if (!replacedAny) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(slim);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析失败一律返回空 map，与"空参数对象"同等处理：都表示没有可替换的字段。 */
    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
