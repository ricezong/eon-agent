package cn.kong.eon.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 共享 ObjectMapper 单例。ObjectMapper 是线程安全的，应全局复用。
 * 已注册 JSR-310 模块，支持 Instant / LocalDateTime 等时间类型。
 */
public final class JsonMapper {
    private static final ObjectMapper INSTANCE = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    public static ObjectMapper get() { return INSTANCE; }

    private JsonMapper() {}
}
