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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * list_dir 工具：列出目录内容。不显示隐藏文件（以 . 开头）。
 */
public class ListDirTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(ListDirTool.class);

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        String targetDir = (String) arguments.get("target_directory");
        if (targetDir == null || targetDir.isBlank()) {
            return ToolOutcome.failure("缺少 'target_directory' 参数");
        }

        PathResolver resolver = new PathResolver(context.workDir(), true);
        Path dirPath;
        try {
            dirPath = resolver.resolve(targetDir);
        } catch (IllegalArgumentException e) {
            return ToolOutcome.failure("路径解析失败: " + e.getMessage());
        }

        if (!Files.exists(dirPath)) {
            return ToolOutcome.failure("目录不存在: " + targetDir);
        }
        if (!Files.isDirectory(dirPath)) {
            return ToolOutcome.failure("不是目录: " + targetDir);
        }

        try (Stream<Path> stream = Files.list(dirPath)) {
            StringBuilder sb = new StringBuilder();
            sb.append("目录内容 ").append(targetDir).append(":\n\n");

            List<Path> entries = stream
                    .filter(p -> !isDotFile(p))
                    .sorted()
                    .toList();

            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                boolean isDir = Files.isDirectory(entry);
                if (isDir) {
                    sb.append("[目录]  ").append(name).append("/\n");
                } else {
                    long size = Files.size(entry);
                    sb.append("[文件] ").append(name).append(" (").append(formatSize(size)).append(")\n");
                }
            }

            sb.append("\n").append(entries.size()).append(" 个条目");
            log.info("list_dir: {} ({} entries)", targetDir, entries.size());

            return ToolOutcome.success(sb.toString());

        } catch (IOException e) {
            log.error("list_dir failed: {}", e.getMessage());
            return ToolOutcome.failure("列出目录失败: " + e.getMessage());
        }
    }

    private boolean isDotFile(Path p) {
        String name = p.getFileName().toString();
        return name.startsWith(".");
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** @Tool 注解方法：供 ToolSpecifications 扫描生成 Schema。 */
    @Tool(name = "list_dir", value = {
            "浏览目录中的文件和子目录。当用户需要查看某个文件夹里有什么文件时使用此工具。",
            "返回文件名、类型和大小信息。"
    })
    public String listDir(
            @P(name = "target_directory", description = "要浏览的目录路径。相对于工作目录，不传则浏览工作目录本身。") String target_directory
    ) {
        return null;
    }

    public static ToolDescriptor descriptor() {
        return ToolDescriptor.fromAnnotated(new ListDirTool(), ToolPermission.READONLY);
    }
}
