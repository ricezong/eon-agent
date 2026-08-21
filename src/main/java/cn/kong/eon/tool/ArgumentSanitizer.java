package cn.kong.eon.tool;

import cn.kong.eon.util.JsonMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 参数类型清洗器。根据工具 Schema 中声明的类型，对 LLM 返回的参数做统一类型转换。
 *
 * 解决的问题：LLM 有时不按 Schema 传参，例如：
 *   - 声明 array，实际传 "[\"a\", \"b\"]"（JSON 字符串）
 *   - 声明 boolean，实际传 "false"（字符串）
 *   - 声明 integer，实际传 "10"（字符串）
 *
 * 清洗后，各工具可直接按声明类型取值，无需各自容错。
 */
public class ArgumentSanitizer {
    private static final Logger log = LoggerFactory.getLogger(ArgumentSanitizer.class);

    private final ObjectMapper mapper = JsonMapper.get();

    /**
     * 根据工具 Schema 清洗参数。
     *
     * @param spec  工具的 ToolSpecification（含参数类型声明）
     * @param args  LLM 返回的原始参数 Map
     * @return 清洗后的新 Map（不修改原 Map）
     */
    public Map<String, Object> sanitize(ToolSpecification spec, Map<String, Object> args) {
        if (args == null || args.isEmpty()) return args;
        if (spec == null || spec.parameters() == null) return args;

        Map<String, JsonSchemaElement> schemaProps = spec.parameters().properties();
        if (schemaProps == null || schemaProps.isEmpty()) return args;

        Map<String, Object> cleaned = new LinkedHashMap<>(args);
        for (Map.Entry<String, JsonSchemaElement> entry : schemaProps.entrySet()) {
            String propName = entry.getKey();
            JsonSchemaElement schema = entry.getValue();
            Object raw = cleaned.get(propName);
            if (raw == null) continue;

            Object fixed = sanitizeValue(propName, schema, raw);
            if (fixed != raw) {
                log.debug("[Sanitize] {}: {} ({}) -> {} ({})",
                        propName, raw, raw.getClass().getSimpleName(),
                        fixed, fixed != null ? fixed.getClass().getSimpleName() : "null");
                cleaned.put(propName, fixed);
            }
        }
        return cleaned;
    }

    private Object sanitizeValue(String propName, JsonSchemaElement schema, Object raw) {
        if (schema instanceof JsonArraySchema) {
            return toArray(raw);
        }
        if (schema instanceof JsonBooleanSchema) {
            return toBoolean(raw);
        }
        if (schema instanceof JsonIntegerSchema) {
            return toInteger(raw);
        }
        if (schema instanceof JsonNumberSchema) {
            return toNumber(raw);
        }
        // String / Object / 其他：不做转换
        return raw;
    }

    /** 转 List：如果 raw 是 String 且能解析为 JSON 数组，则转换。 */
    private Object toArray(Object raw) {
        if (raw instanceof List<?>) return raw;
        if (raw instanceof String s) {
            try {
                Object parsed = mapper.readValue(s.trim(), Object.class);
                if (parsed instanceof List<?> l) return l;
            } catch (Exception ignored) {
            }
        }
        return raw;
    }

    /** 转 Boolean：如果 raw 是 String "true"/"false"（不区分大小写），则转换。 */
    private Object toBoolean(Object raw) {
        if (raw instanceof Boolean) return raw;
        if (raw instanceof String s) {
            String t = s.trim().toLowerCase();
            if ("true".equals(t)) return Boolean.TRUE;
            if ("false".equals(t)) return Boolean.FALSE;
        }
        return raw;
    }

    /** 转 Integer：如果 raw 是 String 且是纯数字，则转换。 */
    private Object toInteger(Object raw) {
        if (raw instanceof Integer) return raw;
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return raw;
    }

    /** 转 Double：如果 raw 是 String 且是数字，则转换。 */
    private Object toNumber(Object raw) {
        if (raw instanceof Double) return raw;
        if (raw instanceof Number n) return n.doubleValue();
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return raw;
    }
}
