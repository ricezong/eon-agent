package cn.kong.eon.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** 共享 ObjectMapper 单例。已注册 JSR-310 模块支持时间类型。 */
public final class JsonMapper {
    private static final ObjectMapper INSTANCE = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    public static ObjectMapper get() { return INSTANCE; }

    private JsonMapper() {}
}
