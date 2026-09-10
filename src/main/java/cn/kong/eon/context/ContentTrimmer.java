package cn.kong.eon.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内容压缩。两种手法：headTail 面向普通文本，skeleton 面向工具参数 JSON。
 */
@Component
public class ContentTrimmer {

    /** 参数中超过该长度的字符串字段才被视为大字段并替换。 */
    private static final int LONG_FIELD_CHARS = 200;

    private final ObjectMapper objectMapper;

    public ContentTrimmer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ═══════════════════════════════════════════════════════════════
    //  普通文本
    // ═══════════════════════════════════════════════════════════════

    /**
     * 头尾保留截断：保留开头与结尾，中段以省略号替代。
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

    // ═══════════════════════════════════════════════════════════════
    //  工具参数 JSON
    // ═══════════════════════════════════════════════════════════════

    /**
     * 参数骨架化：把 JSON 中过长的字符串字段替换为一行说明，其余字段与结构原样保留。
     * 产出始终是合法 JSON，不能用 headTail 截断。
     *
     * @return 骨架 JSON；入参不是 JSON、不含大字段或序列化失败时返回 null
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

    /** 解析失败一律返回空 map。 */
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
