package cn.kong.eon.api.dto;

/**
 * 会话工作区内文件的元信息。
 * <p>
 * 只描述事实，不含渲染建议：能否预览、用哪个渲染器由前端按 mime + 扩展名判定。
 *
 * @param name       文件名（含扩展名）
 * @param path       相对会话工作目录的路径，可作为后续请求的 path 参数
 * @param sizeBytes  真实字节数
 * @param mime       MIME 类型，探测失败时为 application/octet-stream
 * @param binary     是否二进制（前 8KB 含 NUL 或无法按文本解码）
 * @param encoding   实际用于解码的字符集，探测失败时为 unknown
 * @param modifiedAt 最后修改时间（ISO-8601）
 */
public record FileMeta(
        String name,
        String path,
        long sizeBytes,
        String mime,
        boolean binary,
        String encoding,
        String modifiedAt
) {
}
