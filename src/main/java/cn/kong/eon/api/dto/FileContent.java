package cn.kong.eon.api.dto;

/**
 * 文件文本预览结果。
 *
 * @param meta      文件元信息
 * @param content   文本内容，超限时为前 N 字节的解码结果
 * @param truncated 是否因超过预览上限被截断
 * @param limit     本次预览的字节上限，便于前端提示「仅预览前 N」
 */
public record FileContent(
        FileMeta meta,
        String content,
        boolean truncated,
        int limit
) {
}
