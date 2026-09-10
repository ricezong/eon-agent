package cn.kong.eon.agent.event;

/**
 * 工具结果的结构化展示内容。前端按 type 渲染不同卡片。
 *
 * @param type       内容类型：text/file/image/artifact
 * @param text       文本内容（type=text 时）
 * @param filePath   文件路径（type=file 时）
 * @param fileSize   文件大小（type=file 时）
 * @param artifactId artifact 引用 id（type=artifact 时）
 */
public record StructuredContent(
        String type,
        String text,
        String filePath,
        String fileSize,
        String artifactId
) {

    /** 创建文本展示内容。 */
    public static StructuredContent text(String text) {
        return new StructuredContent("text", text, null, null, null);
    }

    /** 创建文件展示内容。 */
    public static StructuredContent file(String filePath, String fileSize) {
        return new StructuredContent("file", null, filePath, fileSize, null);
    }

    /** 创建 artifact 引用展示内容。 */
    public static StructuredContent artifact(String artifactId) {
        return new StructuredContent("artifact", null, null, null, artifactId);
    }
}
