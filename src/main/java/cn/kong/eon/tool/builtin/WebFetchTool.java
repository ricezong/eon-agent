package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolOutcome;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * web_fetch 工具：批量抓取 URL 内容并转为 markdown。
 * - 保持原始协议，不升级或降级
 * - 缓存 TTL 可配置（默认 15 分钟），LRU 容量上限可配置（默认 64）
 * - 内容过大时截断
 */
public class WebFetchTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);

    private static final int TIMEOUT_SECONDS = 30;

    private final int maxContentLength;
    private final long cacheTtlMs;
    private final int cacheMaxEntries;

    private final HttpClient httpClient;

    private final FlexmarkHtmlConverter htmlConverter = FlexmarkHtmlConverter.builder().build();

    // LRU 缓存：URL → (内容, 时间戳)，带容量上限和 TTL 过期
    private final Map<String, CacheEntry> cache;

    /**
     * 仅供测试使用，生产环境通过 {@link #descriptor(int, long, int, HttpClient)} 传入配置。
     */
    public WebFetchTool() {
        this(50000, 15 * 60 * 1000L, 64, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public WebFetchTool(int maxContentLength, long cacheTtlMs, int cacheMaxEntries, HttpClient httpClient) {
        this.maxContentLength = maxContentLength;
        this.cacheTtlMs = cacheTtlMs;
        this.cacheMaxEntries = cacheMaxEntries;
        this.httpClient = httpClient;
        this.cache = Collections.synchronizedMap(
                new LinkedHashMap<String, CacheEntry>(cacheMaxEntries, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                        return size() > cacheMaxEntries;
                    }
                });
    }

    @Override
    public String summarizeArgs(Map<String, Object> args) {
        Object u = args.get("urls");
        int count = (u instanceof List<?> l) ? l.size() : 0;
        return "{urls: " + count + "}";
    }

    /**
     * @Tool 注解方法：供 ToolSpecifications 扫描生成 Schema。
     */
    @Tool(name = "web_fetch", value = {
            "从一个或多个指定 URL 获取内容并返回。用法：以 URL 数组作为输入，抓取 URL 内容并将 HTML 转换为 markdown。",
            "返回所有 URL 的抓取内容。当你需要检索和分析网页内容时使用此工具。",
            "URL 必须是完整的合法 URL。",
            "此工具是只读的。如果内容过大，结果可能会被摘要。支持批量抓取。包含 15 分钟自清理缓存。"
    })
    public String webFetch(
            @P(name = "urls", description = "要获取内容的 URL 数组。每个 URL 必须是完整的合法 URI。") List<String> urls
    ) {
        return null;
    }

    @SuppressWarnings("unchecked")
    /** 仅供测试使用，生产环境通过 {@link #descriptor(int, int, int, HttpClient)} 传入配置。 */
    public static ToolDescriptor descriptor() {
        return ToolDescriptor.fromAnnotated(new WebFetchTool(), ToolPermission.READONLY);
    }

    @SuppressWarnings("unchecked")
    public static ToolDescriptor descriptor(int maxContentLength, int cacheTtlMinutes, int cacheMaxEntries, HttpClient httpClient) {
        long cacheTtlMs = cacheTtlMinutes * 60 * 1000L;
        return ToolDescriptor.fromAnnotated(
                new WebFetchTool(maxContentLength, cacheTtlMs, cacheMaxEntries, httpClient),
                ToolPermission.READONLY);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        Object urlsObj = arguments.get("urls");
        if (!(urlsObj instanceof List<?> rawUrls) || rawUrls.isEmpty()) {
            return ToolOutcome.failure("缺少或空的 'urls' 参数");
        }

        List<String> urls = new ArrayList<>();
        for (Object u : rawUrls) {
            urls.add(String.valueOf(u));
        }

        StringBuilder output = new StringBuilder();
        int success = 0;
        int failed = 0;

        for (String url : urls) {
            try {
                String content = fetchUrl(url);
                output.append("--- ").append(url).append(" ---\n\n");
                output.append(content).append("\n\n");
                success++;
            } catch (Exception e) {
                output.append("--- ").append(url).append("（失败）---\n");
                output.append("错误: ").append(e.getMessage()).append("\n\n");
                failed++;
                log.warn("web_fetch 失败 {}: {}", url, e.getMessage());
            }
        }

        cleanCache();

        output.insert(0, String.format("已获取 %d 个 URL：%d 个成功，%d 个失败。\n\n", urls.size(), success, failed));
        return ToolOutcome.success(output.toString());
    }

    private String fetchUrl(String rawUrl) throws Exception {
        String url = rawUrl.trim();

        String cached = getFromCache(url);
        if (cached != null) {
            log.debug("缓存命中: {}", url);
            return cached;
        }

        log.info("抓取: {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Eon-Agent/1.0 (Web Fetch Tool)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode());
        }

        String body = response.body();
        String contentType = response.headers().firstValue("content-type").orElse("");

        String result;
        if (contentType.contains("text/html") || contentType.contains("application/xhtml")) {
            result = htmlToMarkdown(body);
        } else {
            result = body;
        }

        if (result.length() > maxContentLength) {
            result = result.substring(0, maxContentLength) + "\n... [内容已截断，截断于 " + maxContentLength + " 字符]";
        }

        putToCache(url, result);

        return result;
    }

    private String htmlToMarkdown(String html) {
        String markdown = htmlConverter.convert(html);
        String result = markdown.replaceAll("\\n{3,}", "\n\n").trim();
        if (result.isEmpty()) {
            return "（页面无可读文本内容）";
        }
        return result;
    }

    private String getFromCache(String url) {
        synchronized (cache) {
            CacheEntry entry = cache.get(url);
            if (entry == null) return null;
            if (System.currentTimeMillis() - entry.timestamp() > cacheTtlMs) {
                cache.remove(url);
                return null;
            }
            return entry.content();
        }
    }

    private void putToCache(String url, String content) {
        cache.put(url, new CacheEntry(content, System.currentTimeMillis()));
    }

    private void cleanCache() {
        long now = System.currentTimeMillis();
        synchronized (cache) {
            cache.entrySet().removeIf(e -> now - e.getValue().timestamp() > cacheTtlMs);
        }
    }

    private record CacheEntry(String content, long timestamp) {
    }
}
