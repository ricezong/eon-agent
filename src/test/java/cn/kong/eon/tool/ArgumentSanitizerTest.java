package cn.kong.eon.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ArgumentSanitizerTest {

    private final ArgumentSanitizer sanitizer = new ArgumentSanitizer(new ObjectMapper());

    private ToolSpecification specWith(Map<String, JsonSchemaElement> properties) {
        return ToolSpecification.builder()
                .name("test_tool")
                .description("test")
                .parameters(JsonObjectSchema.builder().addProperties(properties).build())
                .build();
    }

    // ===== Array conversion =====

    @Test
    void sanitize_stringArray_convertedToList() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("items", JsonArraySchema.builder().build());
        ToolSpecification spec = specWith(props);

        Map<String, Object> args = new HashMap<>();
        args.put("items", "[\"a\", \"b\", \"c\"]");

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        assertThat(result.get("items")).isInstanceOf(List.class);
        List<?> list = (List<?>) result.get("items");
        assertThat(list).hasSize(3);
        assertThat(list.get(0)).isEqualTo("a");
        assertThat(list.get(1)).isEqualTo("b");
        assertThat(list.get(2)).isEqualTo("c");
    }

    @Test
    void sanitize_alreadyList_unchanged() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("items", JsonArraySchema.builder().build());
        ToolSpecification spec = specWith(props);

        List<String> original = List.of("a", "b");
        Map<String, Object> args = new HashMap<>();
        args.put("items", original);

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        assertThat(result.get("items")).isSameAs(original);
    }

    @Test
    void sanitize_invalidJsonStringArray_keptAsOriginal() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("items", JsonArraySchema.builder().build());
        ToolSpecification spec = specWith(props);

        String invalid = "not a json array";
        Map<String, Object> args = new HashMap<>();
        args.put("items", invalid);

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        // Should not crash, just return original
        assertThat(result.get("items")).isEqualTo(invalid);
    }

    // ===== Boolean conversion =====

    @Test
    void sanitize_stringTrue_convertedToBoolean() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("flag", JsonBooleanSchema.builder().build());
        ToolSpecification spec = specWith(props);

        Map<String, Object> args = new HashMap<>();
        args.put("flag", "true");

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        assertThat(result.get("flag")).isInstanceOf(Boolean.class);
        assertThat(result.get("flag")).isEqualTo(true);
    }

    @Test
    void sanitize_stringFalse_convertedToBoolean() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("flag", JsonBooleanSchema.builder().build());
        ToolSpecification spec = specWith(props);

        Map<String, Object> args = new HashMap<>();
        args.put("flag", "false");

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        assertThat(result.get("flag")).isEqualTo(false);
    }

    @Test
    void sanitize_stringTrueUppercase_convertedToBoolean() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("flag", JsonBooleanSchema.builder().build());
        ToolSpecification spec = specWith(props);

        Map<String, Object> args = new HashMap<>();
        args.put("flag", "TRUE");

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        assertThat(result.get("flag")).isEqualTo(true);
    }

    @Test
    void sanitize_alreadyBoolean_unchanged() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("flag", JsonBooleanSchema.builder().build());
        ToolSpecification spec = specWith(props);

        Map<String, Object> args = new HashMap<>();
        args.put("flag", true);

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        assertThat(result.get("flag")).isEqualTo(true);
    }

    @Test
    void sanitize_nonBooleanString_keptAsOriginal() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("flag", JsonBooleanSchema.builder().build());
        ToolSpecification spec = specWith(props);

        Map<String, Object> args = new HashMap<>();
        args.put("flag", "maybe");

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        assertThat(result.get("flag")).isEqualTo("maybe");
    }

    // ===== Integer conversion =====

    @Test
    void sanitize_stringInteger_convertedToInteger() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("count", JsonIntegerSchema.builder().build());
        ToolSpecification spec = specWith(props);

        Map<String, Object> args = new HashMap<>();
        args.put("count", "42");

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        assertThat(result.get("count")).isInstanceOf(Integer.class);
        assertThat(result.get("count")).isEqualTo(42);
    }

    @Test
    void sanitize_longConvertedToInteger() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("count", JsonIntegerSchema.builder().build());
        ToolSpecification spec = specWith(props);

        Map<String, Object> args = new HashMap<>();
        args.put("count", 100L);

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        assertThat(result.get("count")).isInstanceOf(Integer.class);
        assertThat(result.get("count")).isEqualTo(100);
    }

    @Test
    void sanitize_alreadyInteger_unchanged() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("count", JsonIntegerSchema.builder().build());
        ToolSpecification spec = specWith(props);

        Map<String, Object> args = new HashMap<>();
        args.put("count", 42);

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        assertThat(result.get("count")).isEqualTo(42);
    }

    @Test
    void sanitize_nonNumericString_keptAsOriginal() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("count", JsonIntegerSchema.builder().build());
        ToolSpecification spec = specWith(props);

        Map<String, Object> args = new HashMap<>();
        args.put("count", "abc");

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        assertThat(result.get("count")).isEqualTo("abc");
    }

    // ===== Number (Double) conversion =====

    @Test
    void sanitize_stringNumber_convertedToDouble() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("rate", JsonNumberSchema.builder().build());
        ToolSpecification spec = specWith(props);

        Map<String, Object> args = new HashMap<>();
        args.put("rate", "3.14");

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        assertThat(result.get("rate")).isInstanceOf(Double.class);
        assertThat(result.get("rate")).isEqualTo(3.14);
    }

    @Test
    void sanitize_integerConvertedToDouble() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("rate", JsonNumberSchema.builder().build());
        ToolSpecification spec = specWith(props);

        Map<String, Object> args = new HashMap<>();
        args.put("rate", 5);

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        assertThat(result.get("rate")).isInstanceOf(Double.class);
        assertThat(result.get("rate")).isEqualTo(5.0);
    }

    @Test
    void sanitize_alreadyDouble_unchanged() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("rate", JsonNumberSchema.builder().build());
        ToolSpecification spec = specWith(props);

        Map<String, Object> args = new HashMap<>();
        args.put("rate", 3.14);

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        assertThat(result.get("rate")).isEqualTo(3.14);
    }

    // ===== Edge cases =====

    @Test
    void sanitize_nullArgs_returnsNull() {
        ToolSpecification spec = specWith(new HashMap<>());
        assertThat(sanitizer.sanitize(spec, null)).isNull();
    }

    @Test
    void sanitize_emptyArgs_returnsEmptyMap() {
        ToolSpecification spec = specWith(new HashMap<>());
        Map<String, Object> result = sanitizer.sanitize(spec, Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    void sanitize_nullSpec_returnsArgsUnchanged() {
        Map<String, Object> args = Map.of("key", "value");
        Map<String, Object> result = sanitizer.sanitize(null, args);
        assertThat(result).isSameAs(args);
    }

    @Test
    void sanitize_nullProperties_returnsArgsUnchanged() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("test").description("test").build();
        Map<String, Object> args = Map.of("key", "value");
        Map<String, Object> result = sanitizer.sanitize(spec, args);
        assertThat(result).isSameAs(args);
    }

    @Test
    void sanitize_nullPropertyValue_skipped() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("name", JsonStringSchema.builder().build());
        props.put("age", JsonIntegerSchema.builder().build());
        ToolSpecification spec = specWith(props);

        Map<String, Object> args = new HashMap<>();
        args.put("name", null);
        args.put("age", null);

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        // Nulls are left as-is (skipped in sanitize loop)
        assertThat(result.get("name")).isNull();
        assertThat(result.get("age")).isNull();
    }

    @Test
    void sanitize_stringSchema_unchanged() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("path", JsonStringSchema.builder().build());
        ToolSpecification spec = specWith(props);

        Map<String, Object> args = new HashMap<>();
        args.put("path", "/some/path");

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        assertThat(result.get("path")).isEqualTo("/some/path");
    }

    @Test
    void sanitize_multipleTypes_allConverted() {
        Map<String, JsonSchemaElement> props = new HashMap<>();
        props.put("items", JsonArraySchema.builder().build());
        props.put("flag", JsonBooleanSchema.builder().build());
        props.put("count", JsonIntegerSchema.builder().build());
        props.put("rate", JsonNumberSchema.builder().build());
        props.put("name", JsonStringSchema.builder().build());
        ToolSpecification spec = specWith(props);

        Map<String, Object> args = new HashMap<>();
        args.put("items", "[\"a\",\"b\"]");
        args.put("flag", "true");
        args.put("count", "42");
        args.put("rate", "3.14");
        args.put("name", "test");

        Map<String, Object> result = sanitizer.sanitize(spec, args);

        assertThat(result.get("items")).isInstanceOf(List.class);
        assertThat(result.get("flag")).isEqualTo(true);
        assertThat(result.get("count")).isEqualTo(42);
        assertThat(result.get("rate")).isEqualTo(3.14);
        assertThat(result.get("name")).isEqualTo("test");
    }
}
