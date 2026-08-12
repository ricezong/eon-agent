package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
 * download 工具：下载文件。
 * 真实实现，使用 Java HttpClient 下载文件到本地。
 * 破坏性工具（写文件系统），需门禁审批。
 */
public class DownloadTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(DownloadTool.class);

    private static final int TIMEOUT_SECONDS = 120;

    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("url", Map.of(
                "type", "string",
                "description", "要下载的文件 URL（必须先通过 web_search/web_read 验证有效，不要编造）",
                "required", true
        ));
        props.put("filename", Map.of(
                "type", "string",
                "description", "保存的文件名（可选，默认从 URL 推断）"
        ));
        String desc = "下载文件到本地（破坏性操作，写文件系统）。"
                + "调用前必须确认 URL 来自 web_search/web_read 的真实结果，禁止编造 URL。";
        return new ToolDescriptor(
                "download",
                desc,
                cn.kong.eon.model.ToolPermission.DESTRUCTIVE,
                ToolDescriptor.buildSpec("download", desc, props),
                new DownloadTool()
        );
    }

    @Override
    public String execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        String url = (String) arguments.get("url");
        if (url == null || url.isBlank()) {
            return "[ERROR] Missing 'url' parameter";
        }

        String filename = (String) arguments.get("filename");
        if (filename == null || filename.isBlank()) {
            filename = inferFilename(url);
        }

        try {
            Path downloadDir = Path.of(context.downloadDir());
            Files.createDirectories(downloadDir);
            Path targetPath = downloadDir.resolve(filename);

            log.info("Download: url='{}' -> '{}'", url, targetPath);

            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET()
                    .build();

            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                return "[ERROR] 下载失败，HTTP 状态码: " + statusCode;
            }

            long bytes;
            try (InputStream is = response.body()) {
                bytes = Files.copy(is, targetPath);
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("unknown");

            StringBuilder sb = new StringBuilder();
            sb.append("[下载成功]\n");
            sb.append("URL: ").append(url).append("\n");
            sb.append("保存路径: ").append(targetPath.toAbsolutePath()).append("\n");
            sb.append("文件大小: ").append(formatSize(bytes)).append(" (").append(bytes).append(" bytes)\n");
            sb.append("Content-Type: ").append(contentType).append("\n");

            return sb.toString();

        } catch (Exception e) {
            log.error("Download failed: {}", e.getMessage(), e);
            return "[ERROR] 下载失败: " + e.getMessage();
        }
    }

    private String inferFilename(String url) {
        try {
            String path = URI.create(url).getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.isBlank() || !name.contains(".")) {
                name = "download_" + System.currentTimeMillis() + ".txt";
            }
            return name;
        } catch (Exception e) {
            return "download_" + System.currentTimeMillis() + ".txt";
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
