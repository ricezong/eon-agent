package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * web_read 工具：读取网页内容。
 * 真实实现，使用 Jsoup 抓取并提取正文文本。
 */
public class WebReadTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(WebReadTool.class);

    private static final int TIMEOUT_MS = 20000;

    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("url", Map.of(
                "type", "string",
                "description", "要读取的网页 URL",
                "required", true
        ));
        return new ToolDescriptor(
                "web_read",
                "读取网页内容，提取正文文本。返回纯文本（去除 HTML 标签）。",
                ToolPermission.READONLY,
                ToolDescriptor.buildSpec("web_read",
                        "读取网页内容，提取正文文本。返回纯文本（去除 HTML 标签）。",
                        props),
                new WebReadTool()
        );
    }

    @Override
    public String execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        String url = (String) arguments.get("url");
        if (url == null || url.isBlank()) {
            return "[ERROR] Missing 'url' parameter";
        }

        try {
            log.info("WebRead: url='{}'", url);
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(TIMEOUT_MS)
                    .followRedirects(true)
                    .get();

            String title = doc.title();
            String text = extractMainText(doc);

            StringBuilder sb = new StringBuilder();
            sb.append("网页标题: ").append(title).append("\n");
            sb.append("URL: ").append(url).append("\n");
            sb.append("内容长度: ").append(text.length()).append(" 字符\n\n");
            sb.append("--- 正文内容 ---\n");
            sb.append(text);

            return sb.toString();

        } catch (Exception e) {
            log.error("WebRead failed: {}", e.getMessage(), e);
            return "[ERROR] 读取网页失败: " + e.getMessage();
        }
    }

    /**
     * 提取网页正文文本。
     * 移除 script/style/nav 等标签，保留正文。
     */
    private String extractMainText(Document doc) {
        doc.select("script, style, nav, header, footer, aside, iframe, noscript").remove();

        String text = doc.body() != null ? doc.body().text() : doc.text();

        // 清理多余空白
        text = text.replaceAll("\\s{3,}", "\n\n");
        text = text.replaceAll("[ \\t]+", " ");

        return text.trim();
    }
}
