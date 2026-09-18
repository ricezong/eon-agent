package cn.kong.eon.tool.model;

import java.util.List;

/**
 * 工具结果的结构化展示内容。前端按 type 选择渲染器。
 * <p>
 * 字段由产出形态决定：file 用 filePath/sizeBytes，dir_list 用 entries，web_page 用 pages，
 * text/artifact 分别用 text/artifactId。未涉及的字段恒为 null；历史账本反序列化时
 * 缺失字段同为 null，前端不得假定非空。
 *
 * @param type       内容类型：text/file/artifact/dir_list/web_page
 * @param text       文本内容（type=text 时）
 * @param filePath   文件路径，相对会话工作目录（type=file 时）
 * @param fileSize   人类可读的体积描述，如「12 行, 345 字符」（type=file 时）
 * @param sizeBytes  真实字节数（type=file 时），前端据此判断是否截断预览
 * @param artifactId artifact 引用 id（type=artifact 时）
 * @param entries    目录条目列表（type=dir_list 时）
 * @param pages      网页预览卡片列表（type=web_page 时）
 */
public record ToolResultView(
        String type,
        String text,
        String filePath,
        String fileSize,
        Long sizeBytes,
        String artifactId,
        List<DirEntry> entries,
        List<WebPage> pages
) {

    /** 创建文本展示内容。 */
    public static ToolResultView text(String text) {
        return new ToolResultView("text", text, null, null, null, null, null, null);
    }

    /** 创建文件展示内容。 */
    public static ToolResultView file(String filePath, String fileSize, long sizeBytes) {
        return new ToolResultView("file", null, filePath, fileSize, sizeBytes, null, null, null);
    }

    /** 创建 artifact 引用展示内容。 */
    public static ToolResultView artifact(String artifactId) {
        return new ToolResultView("artifact", null, null, null, null, artifactId, null, null);
    }

    /** 创建目录列表展示内容。 */
    public static ToolResultView dirList(String path, List<DirEntry> entries) {
        return new ToolResultView("dir_list", path, null, null, null, null, entries, null);
    }

    /** 创建网页预览卡片列表。 */
    public static ToolResultView webPages(List<WebPage> pages) {
        return new ToolResultView("web_page", null, null, null, null, null, null, pages);
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

    /**
     * 网页预览卡片。favicon 是站点声明的原始 URL，由前端直接加载，失败回退占位符。
     *
     * @param url           页面地址
     * @param title         页面标题，解析失败时为 null
     * @param description   摘要（meta description，缺失时取正文首段）
     * @param favicon       站点图标 URL，解析失败时为 null
     * @param siteName      站点名（og:site_name）
     * @param contentLength 抓取到的正文字符数
     * @param success       是否抓取成功；失败时 description 存放错误原因
     */
    public record WebPage(String url, String title, String description, String favicon,
                          String siteName, long contentLength, boolean success) {

        public static WebPage ok(String url, String title, String description, String favicon,
                                 String siteName, long contentLength) {
            return new WebPage(url, title, description, favicon, siteName, contentLength, true);
        }

        public static WebPage failed(String url, String reason) {
            return new WebPage(url, null, reason, null, null, 0, false);
        }
    }
}
