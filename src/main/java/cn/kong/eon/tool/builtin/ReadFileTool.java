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
 * read_file 工具：读取本地文件内容，返回原始文本。
 * 支持 offset/limit 分段读取。
 */
public class ReadFileTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(ReadFileTool.class);

    private static final int DEFAULT_LIMIT = 2000;

    private static final String ARTIFACT_PREFIX = "artifact://";

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        String targetFile = (String) arguments.get("target_file");
        if (targetFile == null || targetFile.isBlank()) {
            return ToolOutcome.failure("缺少 'target_file' 参数");
        }

        Integer offset = arguments.containsKey("offset") ? (Integer) arguments.get("offset") : null;
        Integer limit = arguments.containsKey("limit") ? (Integer) arguments.get("limit") : null;

        // artifact:// 引用：从 ArtifactStore 读取完整内容后分页返回（与普通文件一致），
        // 避免全文一次性进入上下文再次触发外置落盘
        if (targetFile.startsWith(ARTIFACT_PREFIX)) {
            String refId = targetFile.substring(ARTIFACT_PREFIX.length()).trim();
            String content = context.artifactStore().readContent(refId);
            if (content == null) {
                return ToolOutcome.failure("找不到 artifact 引用: " + refId);
            }
            log.info("read_file: artifact://{} ({} 字符)", refId, content.length());
            return paginate(content, offset, limit);
        }

        PathResolver resolver = context.pathResolver();
        Path filePath;
        try {
            filePath = resolver.resolve(targetFile);
        } catch (IllegalArgumentException e) {
            return ToolOutcome.failure("路径解析失败: " + e.getMessage());
        }

        if (!Files.exists(filePath)) {
            return ToolOutcome.failure("文件不存在: " + targetFile);
        }
        if (!Files.isRegularFile(filePath)) {
            return ToolOutcome.failure("不是普通文件: " + targetFile);
        }

        try {
            String content = Files.readString(filePath);
            log.info("read_file: {} ({} 字符)", targetFile, content.length());
            return paginate(content, offset, limit);
        } catch (IOException e) {
            log.error("read_file 失败: {}", e.getMessage());
            return ToolOutcome.failure("读取文件失败: " + e.getMessage());
        }
    }

    /**
     * 统一的行分页：offset 从 1 开始，limit 上限为 DEFAULT_LIMIT。
     * 截断时附带页脚提示，引导调用方继续分页读取，
     * 避免大内容一次性进入上下文再次触发外置落盘。
     */
    private ToolOutcome paginate(String content, Integer offset, Integer limit) {
        String[] lines = content.split("\n", -1);
        int totalLines = lines.length;

        if (totalLines == 1 && lines[0].isEmpty()) {
            return ToolOutcome.success("内容为空。");
        }

        int startLine = offset != null ? Math.max(offset, 1) : 1;
        int maxLines = limit != null ? Math.min(limit, DEFAULT_LIMIT) : DEFAULT_LIMIT;
        int endLine = Math.min(startLine - 1 + maxLines, totalLines);

        if (startLine > totalLines) {
            log.info("read_file: offset {} 超出总行数 {}", startLine, totalLines);
            return ToolOutcome.success("起始行 " + startLine + " 超出总行数（共 " + totalLines + " 行）。");
        }

        int count = endLine - startLine + 1;
        String[] subset = new String[count];
        System.arraycopy(lines, startLine - 1, subset, 0, count);
        String result = String.join("\n", subset);

        if (endLine < totalLines) {
            result += "\n\n(共 " + totalLines + " 行，已显示第 " + startLine + "-" + endLine
                    + " 行，可调整 offset/limit 继续读取)";
        }

        log.info("read_file: 第 {}-{} 行，共 {} 行", startLine, endLine, totalLines);
        return ToolOutcome.success(result);
    }

    @Override
    public String summarizeArgs(Map<String, Object> args) {
        Object p = args.get("target_file");
        return p != null ? "{path: \"" + truncate(String.valueOf(p), 50) + "\"}" : args.toString();
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    @Tool(name = "read_file", value = {
            "读取本地文件的内容。当用户需要查看文件、文档或笔记时使用此工具。",
            "对于较长的文件，可以通过 offset 和 limit 参数分段读取。",
            "如果文件不存在或无法读取，会返回错误信息。",
            "也支持读取 artifact:// 引用（工具结果过长时系统会自动生成此类引用），",
            "且同样支持 offset/limit 分页；内容较大时请分段读取，不要期望一次拿到全部。"
    })
    public String readFile(
            @P(name = "target_file", description = "要读取的文件路径（相对于工作目录，可直接传文件名），或 artifact://art_xxx 形式的引用。") String target_file,
            @P(name = "offset", description = "从第几行开始读取（从 1 开始计数）。不指定则从头读取。", required = false) Integer offset,
            @P(name = "limit", description = "最多读取多少行。不指定则按默认上限读取。", required = false) Integer limit
    ) {
        return null;
    }

    public static ToolDescriptor descriptor() {
        return ToolDescriptor.fromAnnotated(new ReadFileTool(), ToolPermission.READONLY);
    }
}
