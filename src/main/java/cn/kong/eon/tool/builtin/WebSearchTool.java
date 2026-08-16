package cn.kong.eon.tool.builtin;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * web_search 工具：搜索网页。
 * 真实实现，使用百度千帆 AI Search API。
 *
 * 接口文档：
 *   POST https://qianfan.baidubce.com/v2/ai_search/web_search
 *   Authorization: Bearer <API Key>
 *   Body: { messages: [{content, role}], search_source, resource_type_filter, ... }
 *
 * 响应：{ references: [{id, title, url, content, date, type, ...}], request_id }
 *      错误：{ requestId, code, message }
 */
public class WebSearchTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private static final String SEARCH_URL = "https://qianfan.baidubce.com/v2/ai_search/web_search";
    private static final int TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_TOP_K = 10;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();

    private final String apiKey;

    /**
     * 无参构造：从配置文件加载 API Key。
     * 兼容旧调用方式（AgentBootstrap 中 new WebSearchTool()）。
     */
    public WebSearchTool() {
        this.apiKey = loadApiKeyFromConfig();
    }

    /**
     * 显式传入 API Key 的构造函数。
     */
    public WebSearchTool(String apiKey) {
        this.apiKey = apiKey != null ? apiKey : loadApiKeyFromConfig();
    }

    private static String loadApiKeyFromConfig() {
        try {
            AgentConfig config = AgentConfig.loadFromClasspath("config/agent.yaml");
            String key = config.getWebSearch().apiKey;
            return key != null ? key : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("query", Map.of(
                "type", "string",
                "description", "搜索关键词",
                "required", true
        ));
        props.put("max_results", Map.of(
                "type", "integer",
                "description", "最大返回结果数（默认10，最大20）"
        ));
        props.put("site_filter", Map.of(
                "type", "string",
                "description", "限定搜索站点（可选，如 www.weather.com.cn）"
        ));
        props.put("recency_filter", Map.of(
                "type", "string",
                "description", "时效性过滤（可选）：no_limit / day / week / month / year"
        ));
        String desc = "搜索网页，返回搜索结果列表（标题 + URL + 摘要 + 日期）。使用百度千帆 AI Search。";
        return new ToolDescriptor(
                "web_search",
                desc,
                ToolPermission.READONLY,
                ToolDescriptor.buildSpec("web_search", desc, props),
                new WebSearchTool()
        );
    }

    @Override
    public String execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        String query = (String) arguments.get("query");
        if (query == null || query.isBlank()) {
            return "[ERROR] Missing 'query' parameter";
        }

        if (apiKey == null || apiKey.isBlank()) {
            return "[ERROR] 百度千帆 API Key 未配置（请检查 agent.yaml 的 web_search.api_key 或环境变量 QIANFAN_API_KEY）";
        }

        int topK = DEFAULT_TOP_K;
        Object maxObj = arguments.get("max_results");
        if (maxObj instanceof Number n) {
            topK = Math.min(n.intValue(), 20);
        }

        String siteFilter = (String) arguments.get("site_filter");
        String recencyFilter = (String) arguments.get("recency_filter");

        try {
            log.info("WebSearch(千帆): query='{}', topK={}, site={}, recency={}",
                    query, topK, siteFilter, recencyFilter);

            ObjectNode body = buildRequestBody(query, topK, siteFilter, recencyFilter);
            String responseJson = callApi(body);
            return parseResponse(responseJson, query);

        } catch (Exception e) {
            log.error("WebSearch(千帆) failed: {}", e.getMessage(), e);
            return "[ERROR] 搜索失败: " + e.getMessage();
        }
    }

    /**
     * 构建千帆 API 请求体。
     */
    private ObjectNode buildRequestBody(String query, int topK, String siteFilter, String recencyFilter) {
        ObjectNode body = mapper.createObjectNode();

        // messages
        ArrayNode messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("content", query);
        msg.put("role", "user");

        // search_source
        body.put("search_source", "baidu_search_v2");

        // resource_type_filter
        ArrayNode resourceTypeFilter = body.putArray("resource_type_filter");
        ObjectNode webFilter = resourceTypeFilter.addObject();
        webFilter.put("type", "web");
        webFilter.put("top_k", topK);

        // search_filter (可选，限定站点)
        if (siteFilter != null && !siteFilter.isBlank()) {
            ObjectNode searchFilter = body.putObject("search_filter");
            ObjectNode match = searchFilter.putObject("match");
            ArrayNode sites = match.putArray("site");
            sites.add(siteFilter);
        }

        // search_recency_filter (可选)
        if (recencyFilter != null && !recencyFilter.isBlank()) {
            body.put("search_recency_filter", recencyFilter);
        }

        return body;
    }

    /**
     * 调用千帆 API。
     */
    private String callApi(ObjectNode body) throws Exception {
        String requestBody = mapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SEARCH_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();

        log.debug("千帆请求: {}", requestBody);

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("千帆 API 返回非 200: status={}, body={}", response.statusCode(), response.body());
            // 尝试解析错误消息
            try {
                JsonNode errNode = mapper.readTree(response.body());
                if (errNode.has("message")) {
                    return "{\"error\": true, \"message\": \"" + errNode.get("message").asText() + "\"}";
                }
            } catch (Exception ignored) {}
            return "{\"error\": true, \"message\": \"HTTP " + response.statusCode() + "\"}";
        }

        return response.body();
    }

    /**
     * 解析千帆 API 响应。
     */
    private String parseResponse(String responseJson, String query) throws Exception {
        JsonNode root = mapper.readTree(responseJson);

        // 检查错误响应
        if (root.has("code") && root.has("message")) {
            String code = root.path("code").asText();
            String message = root.path("message").asText();
            log.error("千帆 API 错误: code={}, message={}", code, message);
            return "[ERROR] 千帆 API 错误 [" + code + "]: " + message;
        }

        JsonNode references = root.path("references");
        if (!references.isArray() || references.isEmpty()) {
            return "搜索完成，但未找到结果。query: " + query;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("搜索完成，共找到 ").append(references.size()).append(" 条结果：\n\n");

        int count = 0;
        for (JsonNode ref : references) {
            count++;
            String title = ref.path("title").asText("");
            String url = ref.path("url").asText("");
            String content = ref.path("content").asText("");
            String date = ref.path("date").asText("");
            String type = ref.path("type").asText("web");

            sb.append(count).append(". ").append(title).append("\n");
            sb.append("   URL: ").append(url).append("\n");
            sb.append("   类型: ").append(type);
            if (!date.isEmpty()) {
                sb.append(" | 日期: ").append(date);
            }
            sb.append("\n");
            sb.append("   摘要: ").append(content).append("\n\n");
        }

        return sb.toString();
    }
}
