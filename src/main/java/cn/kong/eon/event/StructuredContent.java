package cn.kong.eon.event;

import java.util.List;

/**
 * 工具结果的结构化展示内容。前端按 type 渲染不同卡片。
 *
 * @param type       内容类型：text/file/image/artifact/dir_list
 * @param text       文本内容（type=text 时）
 * @param filePath   文件路径（type=file 时）
 * @param fileSize   文件大小（type=file 时）
 * @param artifactId artifact 引用 id（type=artifact 时）
 * @param entries    目录条目列表（type=dir_list 时）
 */
public record StructuredContent(
        String type,
        String text,
        String filePath,
        String fileSize,
        String artifactId,
        List<DirEntry> entries
) {

    /** 创建文本展示内容。 */
    public static StructuredContent text(String text) {
        return new StructuredContent("text", text, null, null, null, null);
    }

    /** 创建文件展示内容。 */
    public static StructuredContent file(String filePath, String fileSize) {
        return new StructuredContent("file", null, filePath, fileSize, null, null);
    }

    /** 创建 artifact 引用展示内容。 */
    public static StructuredContent artifact(String artifactId) {
        return new StructuredContent("artifact", null, null, null, artifactId, null);
    }

    /** 创建目录列表展示内容。 */
    public static StructuredContent dirList(String path, List<DirEntry> entries) {
        return new StructuredContent("dir_list", path, null, null, null, entries);
    }

    /**
     * 目录条目。
     *
     * @param name     文件/目录名
     * @param type     条目类型：file / dir
     * @param size     文件大小（可读字符串，dir 时为 null）
     */
    public record DirEntry(String name, String type, String size) {
        public static DirEntry file(String name, String size) {
            return new DirEntry(name, "file", size);
        }
        public static DirEntry dir(String name) {
            return new DirEntry(name, "dir", null);
        }
    }
}
