package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolOutcome;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
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
 * - HTTP 自动升级为 HTTPS
 * - 15 分钟自清理缓存
 * - 内容过大时截断
 */
public class WebFetchTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_CONTENT_LENGTH = 50000;
    private static final long CACHE_TTL_MS = 15 * 60 * 1000; // 15 minutes

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // 简单缓存：URL → (内容, 时间戳)
    private final Map<String, long[]> cacheTimestamps = new HashMap<>();
    private final Map<String, String> cacheContent = new HashMap<>();

    @SuppressWarnings("unchecked")
    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();

        // urls: array[string] — items 省略，addProperty 默认使用 JsonStringSchema
        props.put("urls", Map.of(
                "type", "array",
                "description", "要获取内容的 URL 数组。每个 URL 必须是完整的合法 URI。",
                "required", true
        ));

        String desc = "从一个或多个指定 URL 获取内容并返回。用法：以 URL 数组作为输入，抓取 URL 内容并将 HTML 转换为 markdown。"
                + "返回所有 URL 的抓取内容。当你需要检索和分析网页内容时使用此工具。"
                + "URL 必须是完整的合法 URL。HTTP URL 会自动升级为 HTTPS。"
                + "此工具是只读的。如果内容过大，结果可能会被摘要。支持批量抓取。包含 15 分钟自清理缓存。";

        return new ToolDescriptor(
                "web_fetch",
                desc,
                ToolPermission.READONLY,
                ToolDescriptor.buildSpec("web_fetch", desc, props),
                new WebFetchTool()
        );
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
                log.warn("web_fetch failed for {}: {}", url, e.getMessage());
            }
        }

        // 清理过期缓存
        cleanCache();

        output.insert(0, String.format("已获取 %d 个 URL：%d 个成功，%d 个失败。\n\n", urls.size(), success, failed));
        return ToolOutcome.success(output.toString());
    }

    private String fetchUrl(String rawUrl) throws Exception {
        // 保持原始协议，不强制升级 HTTPS（目标服务器可能只支持 HTTP）
        String url = rawUrl.trim();

        // 检查缓存
        String cached = getFromCache(url);
        if (cached != null) {
            log.debug("Cache hit for: {}", url);
            return cached;
        }

        log.info("Fetching: {}", url);

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
            result = htmlToMarkdown(body, url);
        } else {
            // 非HTML直接返回文本
            result = body;
        }

        // 截断
        if (result.length() > MAX_CONTENT_LENGTH) {
            result = result.substring(0, MAX_CONTENT_LENGTH) + "\n... [内容已截断，截断于 " + MAX_CONTENT_LENGTH + " 字符]";
        }

        // 存入缓存
        putToCache(url, result);

        return result;
    }

    /** HTML 转 Markdown（简化版）。 */
    private String htmlToMarkdown(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);

        // 移除 script、style、nav、footer 等非内容元素
        doc.select("script, style, nav, footer, header, noscript, iframe, svg").remove();

        StringBuilder md = new StringBuilder();
        processNode(doc.body(), md, 0);

        // 清理多余空行
        String result = md.toString().replaceAll("\\n{3,}", "\n\n").trim();
        if (result.isEmpty()) {
            return "（页面无可读文本内容）";
        }
        return result;
    }

    private void processNode(Element element, StringBuilder md, int listDepth) {
        for (Node node : element.childNodes()) {
            if (node instanceof TextNode textNode) {
                String text = textNode.getWholeText().replaceAll("\\s+", " ").strip();
                if (!text.isEmpty()) {
                    md.append(text);
                }
            } else if (node instanceof Element child) {
                String tag = child.tagName().toLowerCase();
                switch (tag) {
                    case "h1" -> {
                        md.append("\n\n# ");
                        processNode(child, md, listDepth);
                        md.append("\n\n");
                    }
                    case "h2" -> {
                        md.append("\n\n## ");
                        processNode(child, md, listDepth);
                        md.append("\n\n");
                    }
                    case "h3" -> {
                        md.append("\n\n### ");
                        processNode(child, md, listDepth);
                        md.append("\n\n");
                    }
                    case "h4", "h5", "h6" -> {
                        md.append("\n\n#### ");
                        processNode(child, md, listDepth);
                        md.append("\n\n");
                    }
                    case "p" -> {
                        md.append("\n\n");
                        processNode(child, md, listDepth);
                        md.append("\n");
                    }
                    case "a" -> {
                        String href = child.attr("abs:href");
                        String text = child.text().trim();
                        if (!href.isEmpty() && !text.isEmpty()) {
                            md.append("[").append(text).append("](").append(href).append(")");
                        } else {
                            processNode(child, md, listDepth);
                        }
                    }
                    case "strong", "b" -> {
                        md.append("**");
                        processNode(child, md, listDepth);
                        md.append("**");
                    }
                    case "em", "i" -> {
                        md.append("*");
                        processNode(child, md, listDepth);
                        md.append("*");
                    }
                    case "code" -> {
                        md.append("`").append(child.text()).append("`");
                    }
                    case "pre" -> {
                        md.append("\n```\n");
                        md.append(child.text());
                        md.append("\n```\n\n");
                    }
                    case "ul", "ol" -> {
                        md.append("\n");
                        processList(child, md, listDepth, tag.equals("ol"));
                        md.append("\n");
                    }
                    case "li" -> {
                        // 单独 li 不应出现在这里（由 ul/ol 处理），但兜底
                        md.append("- ");
                        processNode(child, md, listDepth);
                        md.append("\n");
                    }
                    case "br" -> md.append("\n");
                    case "hr" -> md.append("\n---\n\n");
                    case "blockquote" -> {
                        md.append("\n");
                        for (String line : child.text().split("\n")) {
                            md.append("> ").append(line).append("\n");
                        }
                        md.append("\n");
                    }
                    case "table" -> {
                        md.append("\n");
                        processTable(child, md);
                        md.append("\n");
                    }
                    case "img" -> {
                        String src = child.attr("abs:src");
                        String alt = child.attr("alt");
                        if (!src.isEmpty()) {
                            md.append("![").append(alt).append("](").append(src).append(")");
                        }
                    }
                    case "div", "span", "section", "article", "main" -> processNode(child, md, listDepth);
                    default -> processNode(child, md, listDepth);
                }
            }
        }
    }

    private void processList(Element list, StringBuilder md, int depth, boolean ordered) {
        int idx = 1;
        String indent = "  ".repeat(depth);
        for (Element li : list.children()) {
            if (!li.tagName().equalsIgnoreCase("li")) continue;
            md.append(indent);
            if (ordered) {
                md.append(idx++).append(". ");
            } else {
                md.append("- ");
            }
            processNode(li, md, depth + 1);
            md.append("\n");
        }
    }

    private void processTable(Element table, StringBuilder md) {
        var rows = table.select("tr");
        if (rows.isEmpty()) return;

        boolean headerDone = false;
        for (Element row : rows) {
            var cells = row.select("th, td");
            if (cells.isEmpty()) continue;

            md.append("| ");
            for (Element cell : cells) {
                md.append(cell.text().trim()).append(" | ");
            }
            md.append("\n");

            if (!headerDone) {
                md.append("|");
                for (int i = 0; i < cells.size(); i++) {
                    md.append(" --- |");
                }
                md.append("\n");
                headerDone = true;
            }
        }
    }

    // ===== 缓存 =====

    private String getFromCache(String url) {
        long[] ts = cacheTimestamps.get(url);
        if (ts == null) return null;
        if (System.currentTimeMillis() - ts[0] > CACHE_TTL_MS) {
            cacheTimestamps.remove(url);
            cacheContent.remove(url);
            return null;
        }
        return cacheContent.get(url);
    }

    private void putToCache(String url, String content) {
        cacheTimestamps.put(url, new long[]{System.currentTimeMillis()});
        cacheContent.put(url, content);
    }

    private void cleanCache() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, long[]>> it = cacheTimestamps.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (now - entry.getValue()[0] > CACHE_TTL_MS) {
                it.remove();
                cacheContent.remove(entry.getKey());
            }
        }
    }
}
