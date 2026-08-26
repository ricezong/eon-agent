package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolOutcome;
import cn.kong.eon.tool.PathResolver;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * write 工具：写入文件到本地文件系统。
 * 如果文件已存在会覆盖原内容。
 */
public class WriteFileTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(WriteFileTool.class);

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        String filePath = (String) arguments.get("file_path");
        if (filePath == null || filePath.isBlank()) {
            return ToolOutcome.failure("缺少 'file_path' 参数");
        }
        String contents = (String) arguments.get("contents");
        if (contents == null) {
            return ToolOutcome.failure("缺少 'contents' 参数");
        }

        PathResolver resolver = context.pathResolver();
        Path resolvedPath;
        try {
            resolvedPath = resolver.resolve(filePath);
        } catch (IllegalArgumentException e) {
            return ToolOutcome.failure("路径解析失败: " + e.getMessage());
        }

        try {
            Files.createDirectories(resolvedPath.getParent());
            Files.writeString(resolvedPath, contents);

            int lineCount = contents.split("\n", -1).length;
            log.info("write: {} ({} 行, {} 字符)", filePath, lineCount, contents.length());

            return ToolOutcome.success("文件写入成功: " + filePath + "（" + lineCount + " 行）");

        } catch (IOException e) {
            log.error("write 失败: {}", e.getMessage());
            return ToolOutcome.failure("写入文件失败: " + e.getMessage());
        }
    }

    @Override
    public String summarizeArgs(Map<String, Object> args) {
        Object p = args.get("file_path");
        return p != null ? "{path: \"" + truncate(String.valueOf(p), 50) + "\"}" : args.toString();
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    @Tool(name = "write", value = {
            "创建或覆盖文件。当用户需要保存笔记、文档或写入内容到文件时使用此工具。",
            "如果指定路径的文件已存在，会覆盖原文件内容。"
    })
    public String writeFile(
            @P(name = "file_path", description = "要创建或覆盖的文件路径。相对于工作目录，直接传文件名即可，如 '笔记.txt'。") String file_path,
            @P(name = "contents", description = "要写入的文件内容。") String contents
    ) {
        return null;
    }

    public static ToolDescriptor descriptor() {
        return ToolDescriptor.fromAnnotated(new WriteFileTool(), ToolPermission.RESTRICTED_WRITE);
    }
}
