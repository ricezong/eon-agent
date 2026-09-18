package cn.kong.eon.tool.builtin;

import cn.kong.eon.tool.ToolPermission;
import cn.kong.eon.tool.ToolRuntime;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolResult;
import cn.kong.eon.tool.model.ToolResultView;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * web_fetch 工具：批量抓取 URL 内容并转为 markdown。内容过大时截断，含 LRU 缓存。
 * <p>
 * 给模型的是 markdown 全文；给前端的 {@code structured_content} 额外带上
 * 标题 / 摘要 / favicon 等元信息（由 Jsoup 解析），用于渲染网页预览卡片。
 */
public class WebFetchTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);

    private static final int TIMEOUT_SECONDS = 30;

    /** 摘要缺失时，从正文截取的字符数。 */
    private static final int SUMMARY_FALLBACK_CHARS = 200;

    private final int maxContentLength;
    private final long cacheTtlMs;
    private final int cacheMaxEntries;

    private final HttpClient httpClient;

    private final FlexmarkHtmlConverter htmlConverter = FlexmarkHtmlConverter.builder().build();

    /** LRU 缓存：URL → (抓取结果, 时间戳)。 */
    private final Map<String, CacheEntry> cache;

    /** 默认构造，生产环境通过 descriptor(int, long, int, HttpClient) 传入配置。 */
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
    public ToolResult execute(Map<String, Object> arguments, ToolRuntime runtime) {
        Object urlsObj = arguments.get("urls");
        if (!(urlsObj instanceof List<?> rawUrls) || rawUrls.isEmpty()) {
            return ToolResult.failure("缺少或空的 'urls' 参数");
        }

        List<String> urls = new ArrayList<>();
        for (Object u : rawUrls) {
            urls.add(String.valueOf(u));
        }

        StringBuilder output = new StringBuilder();
        List<ToolResultView.WebPage> pages = new ArrayList<>();
        int success = 0;
        int failed = 0;

        for (String url : urls) {
            try {
                Fetched fetched = fetchUrl(url);
                output.append("--- ").append(url).append(" ---\n\n");
                output.append(fetched.content()).append("\n\n");
                pages.add(fetched.page());
                success++;
            } catch (Exception e) {
                output.append("--- ").append(url).append("（失败）---\n");
                output.append("错误: ").append(e.getMessage()).append("\n\n");
                pages.add(ToolResultView.WebPage.failed(url, e.getMessage()));
                failed++;
                log.warn("web_fetch 失败 {}: {}", url, e.getMessage());
            }
        }

        cleanCache();

        output.insert(0, String.format("已获取 %d 个 URL：%d 个成功，%d 个失败。\n\n", urls.size(), success, failed));
        return ToolResult.success(output.toString(), ToolResultView.webPages(pages));
    }

    /** 抓取单个 URL：HTML 转 markdown，超长截断，同时解析页面元信息。 */
    private Fetched fetchUrl(String rawUrl) throws Exception {
        String url = rawUrl.trim();

        Fetched cached = getFromCache(url);
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
        boolean html = contentType.contains("text/html") || contentType.contains("application/xhtml");

        String result = html ? htmlToMarkdown(body) : body;
        if (result.length() > maxContentLength) {
            result = result.substring(0, maxContentLength) + "\n... [内容已截断，截断于 " + maxContentLength + " 字符]";
        }

        ToolResultView.WebPage page = html
                ? parsePage(body, url, result)
                : ToolResultView.WebPage.ok(url, null, summaryOf(result), null, null, result.length());

        Fetched fetched = new Fetched(result, page);
        putToCache(url, fetched);
        return fetched;
    }

    /** HTML 转 markdown 并清理多余空行。 */
    private String htmlToMarkdown(String html) {
        String markdown = htmlConverter.convert(html);
        String result = markdown.replaceAll("\\n{3,}", "\n\n").trim();
        if (result.isEmpty()) {
            return "（页面无可读文本内容）";
        }
        return result;
    }

    // ═══════════════ 元信息解析 ═══════════════

    /** 解析页面元信息。baseUri 传入页面地址，Jsoup 据此把相对链接绝对化。 */
    private static ToolResultView.WebPage parsePage(String html, String url, String markdown) {
        Document doc = Jsoup.parse(html, url);
        String description = firstNonBlank(
                meta(doc, "meta[name=description]"),
                meta(doc, "meta[property=og:description]"),
                summaryOf(markdown));
        return ToolResultView.WebPage.ok(
                url,
                trimToNull(doc.title()),
                description,
                faviconOf(doc, url),
                meta(doc, "meta[property=og:site_name]"),
                markdown.length());
    }

    private static String meta(Document doc, String cssQuery) {
        Element el = doc.selectFirst(cssQuery);
        return el == null ? null : trimToNull(el.attr("content"));
    }

    /** favicon：优先 link[rel~=icon] 的绝对地址，缺失时回退站点根目录的 favicon.ico。 */
    private static String faviconOf(Document doc, String pageUrl) {
        for (Element link : doc.select("link[rel]")) {
            if (!link.attr("rel").toLowerCase().contains("icon")) continue;
            String href = trimToNull(link.attr("abs:href"));
            if (href != null) return href;
        }
        return absolutize(pageUrl, "/favicon.ico");
    }

    /** 相对 URL 转绝对 URL，非法时返回 null。 */
    private static String absolutize(String pageUrl, String href) {
        try {
            return URI.create(pageUrl).resolve(href).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 取正文首段作为摘要：去掉 markdown 标记并压缩空白。 */
    private static String summaryOf(String text) {
        if (text == null || text.isBlank()) return null;
        String flat = text.replaceAll("[#>*`\\-_\\[\\]()!]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (flat.isEmpty()) return null;
        return flat.length() <= SUMMARY_FALLBACK_CHARS
                ? flat : flat.substring(0, SUMMARY_FALLBACK_CHARS) + "…";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ═══════════════ 缓存 ═══════════════

    /** 从缓存获取（检查 TTL）。 */
    private Fetched getFromCache(String url) {
        synchronized (cache) {
            CacheEntry entry = cache.get(url);
            if (entry == null) return null;
            if (System.currentTimeMillis() - entry.timestamp() > cacheTtlMs) {
                cache.remove(url);
                return null;
            }
            return entry.fetched();
        }
    }

    /** 写入缓存。 */
    private void putToCache(String url, Fetched fetched) {
        cache.put(url, new CacheEntry(fetched, System.currentTimeMillis()));
    }

    /** 清理过期缓存条目。 */
    private void cleanCache() {
        long now = System.currentTimeMillis();
        synchronized (cache) {
            cache.entrySet().removeIf(e -> now - e.getValue().timestamp() > cacheTtlMs);
        }
    }

    /** 抓取结果：给模型的正文 + 给前端的预览卡片。 */
    private record Fetched(String content, ToolResultView.WebPage page) {
    }

    /** 缓存条目。 */
    private record CacheEntry(Fetched fetched, long timestamp) {
    }
}
