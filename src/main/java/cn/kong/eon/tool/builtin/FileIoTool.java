package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolOutcome;
import cn.kong.eon.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/** file_io 工具：文件读写。支持 read/write/list/delete，路径相对于 workDir。 */
public class FileIoTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(FileIoTool.class);

    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("operation", Map.of(
                "type", "string",
                "description", "操作类型：read(读文件) | write(写文件) | list(列目录) | delete(删文件)",
                "required", true
        ));
        props.put("path", Map.of(
                "type", "string",
                "description", "相对路径（相对于工作目录），如 notes/summary.txt",
                "required", true
        ));
        props.put("content", Map.of(
                "type", "string",
                "description", "写入内容（operation=write 时必填）"
        ));
        props.put("append", Map.of(
                "type", "boolean",
                "description", "是否追加（operation=write 时可选，默认 false 即覆盖）"
        ));

        String desc = "文件读写（工作目录内）。write 自动创建父目录；"
                + "路径禁止含 ..（防穿越）。";
        return new ToolDescriptor(
                "file_io",
                desc,
                ToolPermission.RESTRICTED_WRITE,
                ToolDescriptor.buildSpec("file_io", desc, props),
                new FileIoTool()
        );
    }

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        String operation = (String) arguments.get("operation");
        String relativePath = (String) arguments.get("path");

        if (operation == null || operation.isBlank()) {
            return ToolOutcome.failure("缺少 'operation' 参数");
        }
        if (relativePath == null || relativePath.isBlank()) {
            return ToolOutcome.failure("缺少 'path' 参数");
        }

        try {
            Path resolved = resolveSafe(context.workDir(), relativePath);

            return switch (operation.toLowerCase()) {
                case "read" -> doRead(resolved);
                case "write" -> doWrite(resolved, arguments);
                case "list" -> doList(resolved);
                case "delete" -> doDelete(resolved);
                default -> ToolOutcome.failure("未知 operation: " + operation + "，支持 read/write/list/delete");
            };
        } catch (SecurityException e) {
            return ToolOutcome.failure("路径不安全: " + e.getMessage());
        } catch (Exception e) {
            log.error("file_io failed: op={}, path={}", operation, relativePath, e);
            return ToolOutcome.failure(e.getMessage());
        }
    }

    /** 解析路径，禁止 .. 穿越。 */
    private Path resolveSafe(String workDir, String relativePath) {
        Path base = Path.of(workDir).toAbsolutePath().normalize();
        Path resolved = base.resolve(relativePath).normalize();

        if (!resolved.startsWith(base)) {
            throw new SecurityException("路径超出工作目录: " + relativePath);
        }
        return resolved;
    }

    private ToolOutcome doRead(Path path) throws IOException {
        if (!Files.exists(path)) {
            return ToolOutcome.failure("文件不存在: " + path);
        }
        if (Files.isDirectory(path)) {
            return ToolOutcome.failure("路径是目录，不是文件: " + path);
        }

        String content = Files.readString(path);
        StringBuilder sb = new StringBuilder();
        sb.append("[读取成功] ").append(path.getFileName()).append("\n");
        sb.append("路径: ").append(path).append("\n");
        sb.append("大小: ").append(Files.size(path)).append(" bytes\n\n");        sb.append(content);
        return ToolOutcome.success(sb.toString());
    }

    private ToolOutcome doWrite(Path path, Map<String, Object> arguments) throws IOException {
        Object contentVal = arguments.get("content");
        if (contentVal == null) {
            return ToolOutcome.failure("write 操作需要 'content' 参数");
        }
        String content = String.valueOf(contentVal);
        Boolean appendVal = (Boolean) arguments.get("append");
        boolean append = appendVal != null && appendVal;

        Files.createDirectories(path.getParent());
        if (append) {
            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        log.info("file_io write: path={}, {} bytes, append={}", path, content.length(), append);

        StringBuilder sb = new StringBuilder();
        sb.append("[写入成功]\n");
        sb.append("路径: ").append(path).append("\n");
        sb.append("写入: ").append(content.length()).append(" 字符\n");
        sb.append("模式: ").append(append ? "追加" : "覆盖");
        return ToolOutcome.success(sb.toString());
    }

    private ToolOutcome doList(Path path) throws IOException {
        if (!Files.exists(path)) {
            return ToolOutcome.failure("路径不存在: " + path);
        }
        if (!Files.isDirectory(path)) {
            return ToolOutcome.failure("路径是文件，不是目录: " + path);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[目录列表] ").append(path).append("\n");

        try (Stream<Path> stream = Files.list(path)) {
            var items = stream.sorted().toList();
            if (items.isEmpty()) {
                sb.append("（空目录）\n");
            } else {
                for (Path item : items) {
                    String name = item.getFileName().toString();
                    String type = Files.isDirectory(item) ? "DIR " : "FILE";
                    long size = Files.isRegularFile(item) ? Files.size(item) : 0;
                    sb.append(type).append("  ").append(name);
                    if (size > 0) {
                        sb.append("  (").append(FileUtils.formatSize(size)).append(")");
                    }
                    sb.append("\n");
                }
            }
            sb.append("共 ").append(items.size()).append(" 项");
        }

        return ToolOutcome.success(sb.toString());
    }

    private ToolOutcome doDelete(Path path) throws IOException {
        if (!Files.exists(path)) {
            return ToolOutcome.failure("文件不存在: " + path);
        }
        if (Files.isDirectory(path)) {
            return ToolOutcome.failure("不支持删除目录: " + path);
        }

        Files.delete(path);
        log.info("file_io delete: {}", path);

        return ToolOutcome.success("[删除成功] " + path);
    }
}
