package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolOutcome;
import cn.kong.eon.tool.PathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * delete_file 工具：删除指定文件。失败优雅返回。
 */
public class DeleteFileTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(DeleteFileTool.class);

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        String targetFile = (String) arguments.get("target_file");
        if (targetFile == null || targetFile.isBlank()) {
            return ToolOutcome.failure("缺少 'target_file' 参数");
        }

        // explanation 是可选参数，记录到日志
        String explanation = (String) arguments.get("explanation");
        if (explanation != null) {
            log.info("delete_file explanation: {}", explanation);
        }

        PathResolver resolver = new PathResolver(context.workDir(), true);
        Path resolvedPath;
        try {
            resolvedPath = resolver.resolve(targetFile);
        } catch (IllegalArgumentException e) {
            return ToolOutcome.failure("路径解析失败: " + e.getMessage());
        }

        if (!Files.exists(resolvedPath)) {
            return ToolOutcome.failure("文件不存在（可能已删除或从未存在）: " + targetFile);
        }
        if (!Files.isRegularFile(resolvedPath)) {
            return ToolOutcome.failure("不是普通文件（无法删除目录）: " + targetFile);
        }

        try {
            Files.delete(resolvedPath);

            log.info("delete_file: {} (explanation: {})", targetFile, explanation != null ? explanation : "(none)");

            return ToolOutcome.success("文件删除成功: " + targetFile);

        } catch (IOException e) {
            log.error("delete_file failed: {}", e.getMessage());
            return ToolOutcome.failure("删除文件失败（可能被锁定或只读）: " + e.getMessage());
        } catch (SecurityException e) {
            log.error("delete_file rejected (security): {}", e.getMessage());
            return ToolOutcome.failure("因安全原因拒绝删除: " + e.getMessage());
        }
    }

    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("target_file", Map.of(
                "type", "string",
                "description", "要删除的文件或目录路径。相对于工作目录，可直接传文件名。",
                "required", true
        ));
        String desc = "删除指定文件或目录。用法：删除文件或目录。永久删除，不可恢复。"
                + "危险：删除前需要确认。";
        return new ToolDescriptor(
                "delete_file",
                desc,
                ToolPermission.RESTRICTED_WRITE,
                ToolDescriptor.buildSpec("delete_file", desc, props),
                new DeleteFileTool()
        );
    }
}
