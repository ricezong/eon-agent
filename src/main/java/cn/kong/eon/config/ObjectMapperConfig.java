package cn.kong.eon.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Jackson ObjectMapper 配置。通过 Spring {@code @Bean} 注册，
 * 可通过构造函数注入到需要序列化/反序列化的组件。
 */
@Configuration
public class ObjectMapperConfig {

    /**
     * 通用 ObjectMapper：注册 JavaTimeModule，禁用日期时间戳写入。
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * 缩进输出的 ObjectMapper 副本，用于配置文件/快照等需要人类可读的场景。
     */
    @Bean("indentObjectMapper")
    public ObjectMapper indentObjectMapper() {
        return objectMapper().copy().enable(SerializationFeature.INDENT_OUTPUT);
    }
}
