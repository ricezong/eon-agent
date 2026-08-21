package cn.kong.eon.util;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 共享 ObjectMapper 单例。ObjectMapper 是线程安全的，应全局复用。
 */
public final class JsonMapper {
    private static final ObjectMapper INSTANCE = new ObjectMapper();

    public static ObjectMapper get() { return INSTANCE; }

    private JsonMapper() {}
}
