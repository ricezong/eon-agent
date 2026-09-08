package cn.kong.eon.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * ObjectMapper 静态工具类。统一创建和管理 ObjectMapper 实例，
 * 任何需要序列化/反序列化的组件直接通过静态方法获取，无需构造函数注入。
 * <p>
 * 通用实例注册了 {@link JavaTimeModule} 并禁用日期时间戳写入，
 * 适用于 JSON 序列化/反序列化场景。
 * <p>
 * YAML 实例额外使用 {@link YAMLFactory} 和 {@link PropertyNamingStrategies#SNAKE_CASE}，
 * 专用于 YAML 配置文件绑定。
 */
public final class ObjectMapperConfig {

    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();
    private static final ObjectMapper YAML_MAPPER = createYamlMapper();

    private ObjectMapperConfig() {
    }

    /** 获取通用 ObjectMapper 实例。 */
    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }

    /** 获取 YAML 专用 ObjectMapper 实例（snake_case 映射）。 */
    public static ObjectMapper getYamlMapper() {
        return YAML_MAPPER;
    }

    /** 创建通用 ObjectMapper：注册 JavaTimeModule，禁用日期时间戳写入。 */
    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /** 创建 YAML 专用 ObjectMapper：snake_case 属性命名策略。 */
    private static ObjectMapper createYamlMapper() {
        return new ObjectMapper(new YAMLFactory())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
