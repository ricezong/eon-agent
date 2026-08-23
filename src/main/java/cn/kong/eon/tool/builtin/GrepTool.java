package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.PathResolver;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * grep 工具：在文件或目录中搜索内容。
 * 精简版：4 个参数（pattern / path / case_insensitive / context_lines）。
 * 固定 content 输出模式，支持简单的模式匹配。
 */
public class GrepTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(GrepTool.class);

    private static final int MAX_MATCH_LINES = 500;
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int DEFAULT_CONTEXT_LINES = 1;

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        String patternStr = (String) arguments.get("pattern");
        if (patternStr == null || patternStr.isBlank()) {
            return ToolOutcome.failure("缺少 'pattern' 参数");
        }

        String searchPath = (String) arguments.get("path");
        boolean caseInsensitive = "true".equalsIgnoreCase(String.valueOf(arguments.get("case_insensitive")));
        int contextLines = parseIntOrDefault(arguments.get("context_lines"), DEFAULT_CONTEXT_LINES);

        // 构建搜索模式
        int flags = 0;
        if (caseInsensitive) flags |= Pattern.CASE_INSENSITIVE;
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr, flags);
        } catch (Exception e) {
            return ToolOutcome.failure("无效的搜索模式: " + e.getMessage());
        }

        // 解析搜索路径
        PathResolver resolver = new PathResolver(context.workDir(), true);
        Path rootPath;
        try {
            rootPath = searchPath != null && !searchPath.isBlank()
                    ? resolver.resolve(searchPath)
                    : resolver.workspace();
        } catch (IllegalArgumentException e) {
            return ToolOutcome.failure("路径解析失败: " + e.getMessage());
        }

        if (!Files.exists(rootPath)) {
            return ToolOutcome.failure("路径不存在: " + searchPath);
        }

        // 收集匹配结果
        List<MatchEntry> allMatches = new ArrayList<>();
        int[] totalMatches = {0};

        try {
            if (Files.isRegularFile(rootPath)) {
                searchInFile(rootPath, rootPath, pattern, contextLines, allMatches, totalMatches);
            } else if (Files.isDirectory(rootPath)) {
                Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (allMatches.size() >= MAX_MATCH_LINES) return FileVisitResult.TERMINATE;
                        searchInFile(file, rootPath, pattern, contextLines, allMatches, totalMatches);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException e) {
            log.error("grep failed: {}", e.getMessage());
            return ToolOutcome.failure("搜索失败: " + e.getMessage());
        }

        // 构建输出
        StringBuilder sb = new StringBuilder();
        int fileCount = (int) allMatches.stream().map(m -> m.filePath).distinct().count();
        sb.append("搜索 \"").append(patternStr).append("\" 在 ").append(fileCount).append(" 个文件中，")
          .append(totalMatches[0]).append(" 处匹配。\n\n");

        boolean truncated = false;
        for (MatchEntry entry : allMatches) {
            if (sb.length() > 50000) {
                truncated = true;
                break;
            }
            // 文件名（目录搜索时显示）
            if (Files.isDirectory(rootPath)) {
                Path relative = rootPath.relativize(entry.filePath);
                sb.append("--- ").append(relative).append(" ---\n");
            }
            for (ContextLine line : entry.lines) {
                sb.append(line.lineNumber).append(": ");
                if (line.isMatch) {
                    sb.append(">> ").append(line.content);
                } else {
                    sb.append("   ").append(line.content);
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (truncated) {
            sb.append("... (结果已截断，显示部分匹配)\n");
        }

        log.info("grep: pattern='{}' files={} matches={}", patternStr, fileCount, totalMatches[0]);
        return ToolOutcome.success(sb.toString().trim());
    }

    private void searchInFile(Path file, Path basePath, Pattern pattern, int contextLines,
                              List<MatchEntry> results, int[] totalMatches) {
        if (results.size() >= MAX_MATCH_LINES) return;
        if (!Files.isRegularFile(file)) return;

        try {
            long size = Files.size(file);
            if (size > MAX_FILE_SIZE) return;

            String content = Files.readString(file);
            String[] lines = content.split("\n", -1);
            if (lines.length == 0) return;

            Matcher matcher = pattern.matcher("");

            MatchEntry entry = null;

            for (int i = 0; i < lines.length; i++) {
                matcher.reset(lines[i]);
                if (matcher.find()) {
                    totalMatches[0]++;

                    if (entry == null) {
                        entry = new MatchEntry(file);
                        results.add(entry);
                    }

                    int start = Math.max(0, i - contextLines);
                    int end = Math.min(lines.length - 1, i + contextLines);

                    for (int j = start; j <= end; j++) {
                        entry.lines.add(new ContextLine(j + 1, lines[j], j == i));
                    }

                    if (results.size() >= MAX_MATCH_LINES) return;
                }
            }
        } catch (IOException e) {
            // 跳过无法读取的文件
        }
    }

    private int parseIntOrDefault(Object value, int defaultVal) {
        if (value == null) return defaultVal;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    // === 内部数据结构 ===

    private static class MatchEntry {
        final Path filePath;
        final List<ContextLine> lines = new ArrayList<>();

        MatchEntry(Path filePath) {
            this.filePath = filePath;
        }
    }

    private static class ContextLine {
        final int lineNumber;
        final String content;
        final boolean isMatch;

        ContextLine(int lineNumber, String content, boolean isMatch) {
            this.lineNumber = lineNumber;
            this.content = content;
            this.isMatch = isMatch;
        }
    }

    // === Schema ===

    @SuppressWarnings("unchecked")
    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("pattern", Map.of(
                "type", "string",
                "description", "要搜索的内容（支持简单的模式匹配）。",
                "required", true
        ));
        props.put("path", Map.of(
                "type", "string",
                "description", "要搜索的文件或目录路径。相对于工作目录，不指定则搜索工作目录。"
        ));
        props.put("case_insensitive", Map.of(
                "type", "string",
                "description", "是否忽略大小写。传 \"true\" 启用。默认：false。"
        ));
        props.put("context_lines", Map.of(
                "type", "string",
                "description", "匹配行前后显示的上下文行数。默认：1。"
        ));

        String desc = "在文件或目录中搜索指定内容。当需要在文件中查找某段文字或某个关键词时使用此工具。"
                + "返回匹配的行及行号，默认包含上下文行。"
                + "搜索范围可以是一个文件或整个目录。";

        return new ToolDescriptor(
                "grep",
                desc,
                ToolPermission.READONLY,
                ToolDescriptor.buildSpec("grep", desc, props),
                new GrepTool()
        );
    }
}
