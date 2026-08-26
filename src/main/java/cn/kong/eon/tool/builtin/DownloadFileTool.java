package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.PathResolver;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolOutcome;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * download_file 工具：从 URL 下载文件到本地文件系统。
 * - 流式写入，不经过模型上下文窗口（不受 50KB 限制）
 * - 支持大文件（上限 100MB）
 */
public class DownloadFileTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(DownloadFileTool.class);

    private static final int TIMEOUT_SECONDS = 60;

    private final long maxFileSize;

    private final HttpClient httpClient;

    public DownloadFileTool() {
        this(100L * 1024 * 1024);
    }

    public DownloadFileTool(long maxFileSize) {
        this(maxFileSize, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public DownloadFileTool(long maxFileSize, HttpClient httpClient) {
        this.maxFileSize = maxFileSize;
        this.httpClient = httpClient;
    }

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        String url = (String) arguments.get("url");
        if (url == null || url.isBlank()) {
            return ToolOutcome.failure("缺少 'url' 参数");
        }

        String fileName = (String) arguments.get("file_name");
        if (fileName == null || fileName.isBlank()) {
            return ToolOutcome.failure("缺少 'file_name' 参数");
        }

        String resolvedUrl = url.trim();

        PathResolver resolver = context.pathResolver();
        Path localPath;
        try {
            localPath = resolver.resolve(fileName);
        } catch (IllegalArgumentException e) {
            return ToolOutcome.failure("路径解析失败: " + e.getMessage());
        }

        try {
            log.info("download_file: {} -> {}", resolvedUrl, localPath);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolvedUrl))
                    .header("User-Agent", "Eon-Agent/1.0 (Download Tool)")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                return ToolOutcome.failure("下载失败：HTTP " + response.statusCode());
            }

            long contentLength = response.headers()
                    .firstValueAsLong("content-length")
                    .orElse(-1);
            if (contentLength > maxFileSize) {
                return ToolOutcome.failure("文件过大：" + formatSize(contentLength)
                        + "，上限 " + formatSize(maxFileSize));
            }

            Files.createDirectories(localPath.getParent());

            long bytesWritten;
            try (InputStream is = response.body()) {
                bytesWritten = Files.copy(is, localPath);
            }

            // 再次检查实际写入大小
            if (bytesWritten > maxFileSize) {
                Files.deleteIfExists(localPath);
                return ToolOutcome.failure("文件过大：" + formatSize(bytesWritten)
                        + "，上限 " + formatSize(maxFileSize));
            }

            log.info("download_file done: {} ({} bytes)", localPath, bytesWritten);

            return ToolOutcome.success("文件下载成功: " + fileName
                    + "（" + formatSize(bytesWritten) + "）");

        } catch (java.net.ConnectException e) {
            log.error("download_file connect failed: {}", e.getMessage());
            return ToolOutcome.failure("连接失败: " + e.getMessage());
        } catch (java.net.SocketTimeoutException e) {
            log.error("download_file timeout: {}", e.getMessage());
            return ToolOutcome.failure("下载超时: " + e.getMessage());
        } catch (Exception e) {
            log.error("download_file failed: {}", e.getMessage(), e);
            return ToolOutcome.failure("下载失败: " + e.getMessage());
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    public String summarizeArgs(Map<String, Object> args) {
        Object u = args.get("url");
        return u != null ? "{url: \"" + truncate(String.valueOf(u), 50) + "\"}" : args.toString();
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /** @Tool 注解方法：供 ToolSpecifications 扫描生成 Schema。 */
    @Tool(name = "download_file", value = {
            "从指定 URL 下载文件并保存到本地。当用户要求下载文件、保存远程内容到本地时使用此工具。",
            "文件以流式方式直接写入本地磁盘，不经过对话上下文，支持大文件（上限 100MB）。"
    })
    public String downloadFile(
            @P(name = "url", description = "要下载的文件 URL。必须是完整的合法 URL。") String url,
            @P(name = "file_name", description = "保存的文件名称") String file_name
    ) {
        return null;
    }

    /** 仅供测试使用，生产环境通过 {@link #descriptor(long, HttpClient)} 传入配置。 */
    public static ToolDescriptor descriptor() {
        return ToolDescriptor.fromAnnotated(new DownloadFileTool(), ToolPermission.RESTRICTED_WRITE);
    }

    public static ToolDescriptor descriptor(long maxFileSize, HttpClient httpClient) {
        return ToolDescriptor.fromAnnotated(new DownloadFileTool(maxFileSize, httpClient), ToolPermission.RESTRICTED_WRITE);
    }
}
