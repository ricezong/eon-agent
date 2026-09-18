package cn.kong.eon.tool;

import java.util.List;
import java.util.Map;

/**
 * 工具向用户发起的提问请求。工具只负责产出它，转成事件发出由调度器负责——
 * 工具拿不到 RunContext，不该自己 emit。
 */
public record InteractionRequest(
        String title,
        List<Map<String, Object>> questions
) {
}
