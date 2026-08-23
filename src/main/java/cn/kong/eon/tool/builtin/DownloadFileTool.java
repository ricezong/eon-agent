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

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * download_file 工具：从 URL 下载文件到本地文件系统。
 * - 流式写入，不经过模型上下文窗口（不受 50KB 限制）
 * - 保持原始内容，不做 HTML→markdown 转换
 * - 支持大文件（上限 100MB）
 */
public class DownloadFileTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(DownloadFileTool.class);

    private static final int TIMEOUT_SECONDS = 60;
    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024; // 100MB

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        String url = (String) arguments.get("url");
        if (url == null || url.isBlank()) {
            return ToolOutcome.failure("缺少 'url' 参数");
        }

        String filePath = (String) arguments.get("file_path");
        if (filePath == null || filePath.isBlank()) {
            return ToolOutcome.failure("缺少 'file_path' 参数");
        }

        // 保持原始协议，不强制升级 HTTPS（目标服务器可能只支持 HTTP）
        String resolvedUrl = url.trim();

        // 解析本地路径
        PathResolver resolver = new PathResolver(context.workDir(), true);
        Path localPath;
        try {
            localPath = resolver.resolve(filePath);
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

            // 检查 Content-Length（如果服务器提供）
            long contentLength = response.headers()
                    .firstValueAsLong("content-length")
                    .orElse(-1);
            if (contentLength > MAX_FILE_SIZE) {
                return ToolOutcome.failure("文件过大：" + formatSize(contentLength)
                        + "，上限 " + formatSize(MAX_FILE_SIZE));
            }

            // 确保父目录存在
            Files.createDirectories(localPath.getParent());

            // 流式写入
            long bytesWritten;
            try (InputStream is = response.body()) {
                bytesWritten = Files.copy(is, localPath);
            }

            // 再次检查实际写入大小
            if (bytesWritten > MAX_FILE_SIZE) {
                Files.deleteIfExists(localPath);
                return ToolOutcome.failure("文件过大：" + formatSize(bytesWritten)
                        + "，上限 " + formatSize(MAX_FILE_SIZE));
            }

            log.info("download_file done: {} ({} bytes)", localPath, bytesWritten);

            return ToolOutcome.success("文件下载成功: " + filePath
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

    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("url", Map.of(
                "type", "string",
                "description", "要下载的文件 URL。必须是完整的合法 URL。",
                "required", true
        ));
        props.put("filename", Map.of(
                "type", "string",
                "description", "保存的文件名（可选，默认从 URL 推断）",
                "required", true
        ));

        String desc = "从指定 URL 下载文件并保存到本地。当用户要求下载文件、保存远程内容到本地时使用此工具。"
                + "文件以流式方式直接写入本地磁盘，不经过对话上下文，支持大文件（上限 100MB）。"
                + "保持原始内容，不做格式转换。";

        return new ToolDescriptor(
                "download_file",
                desc,
                ToolPermission.RESTRICTED_WRITE,
                ToolDescriptor.buildSpec("download_file", desc, props),
                new DownloadFileTool()
        );
    }
}
