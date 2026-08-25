package cn.kong.eon.tool;

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
 * 参数类型清洗器。根据工具 Schema 声明的类型，对 LLM 返回的参数做统一类型转换。
 * 解决 LLM 不按 Schema 传参的问题（如声明 array 实际传 JSON 字符串）。
 */
public class ArgumentSanitizer {
    private static final Logger log = LoggerFactory.getLogger(ArgumentSanitizer.class);

    private final ObjectMapper mapper;

    public ArgumentSanitizer(ObjectMapper objectMapper) {
        this.mapper = objectMapper;
    }

    /**
     * 根据工具 Schema 清洗参数，返回清洗后的新 Map（不修改原 Map）。
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
