package cn.kong.eon.tool;

import cn.kong.eon.tool.model.ToolResultView;

/**
 * 工具执行结果。封装成功/失败状态、给模型的内容与给前端的结构化展示内容。
 */
public record ToolResult(
        boolean success,
        String content,                    // 给模型的内容
        ToolResultView toolResultView  // 给前端展示的结构化内容
) {

    /** 构建成功结果：纯文本。 */
    public static ToolResult success(String content) {
        return new ToolResult(true, content != null ? content : "",
                ToolResultView.text(content != null ? content : ""));
    }

    /** 构建成功结果：文件类。 */
    public static ToolResult successFile(String modelContent, String filePath, String fileSize) {
        return new ToolResult(true, modelContent,
                ToolResultView.file(filePath, fileSize));
    }

    /** 构建成功结果：artifact 引用。 */
    public static ToolResult successArtifact(String modelContent, String artifactId) {
        return new ToolResult(true, modelContent,
                ToolResultView.artifact(artifactId));
    }

    /** 构建成功结果：自定义结构化内容。 */
    public static ToolResult success(String modelContent, ToolResultView structured) {
        return new ToolResult(true, modelContent != null ? modelContent : "", structured);
    }

    /** 构建失败结果。 */
    public static ToolResult failure(String content) {
        return new ToolResult(false, content != null ? content : "",
                ToolResultView.text(content != null ? content : ""));
    }
}
