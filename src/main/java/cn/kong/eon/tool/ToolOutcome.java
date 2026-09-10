package cn.kong.eon.tool;

import cn.kong.eon.event.StructuredContent;

/**
 * 工具执行结果。封装成功/失败状态、给模型的内容与给前端的结构化展示内容。
 */
public record ToolOutcome(
        boolean success,
        String content,                    // 给模型的内容
        StructuredContent structuredContent  // 给前端展示的结构化内容
) {

    /** 构建成功结果：纯文本。 */
    public static ToolOutcome success(String content) {
        return new ToolOutcome(true, content != null ? content : "",
                StructuredContent.text(content != null ? content : ""));
    }

    /** 构建成功结果：文件类。 */
    public static ToolOutcome successFile(String modelContent, String filePath, String fileSize) {
        return new ToolOutcome(true, modelContent,
                StructuredContent.file(filePath, fileSize));
    }

    /** 构建成功结果：artifact 引用。 */
    public static ToolOutcome successArtifact(String modelContent, String artifactId) {
        return new ToolOutcome(true, modelContent,
                StructuredContent.artifact(artifactId));
    }

    /** 构建成功结果：自定义结构化内容。 */
    public static ToolOutcome success(String modelContent, StructuredContent structured) {
        return new ToolOutcome(true, modelContent != null ? modelContent : "", structured);
    }

    /** 构建失败结果。 */
    public static ToolOutcome failure(String content) {
        return new ToolOutcome(false, content != null ? content : "",
                StructuredContent.text(content != null ? content : ""));
    }
}
