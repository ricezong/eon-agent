package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolOutcome;
import cn.kong.eon.util.JsonMapper;
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

/** web_search 工具：使用百度千帆 AI Search API 搜索网页。 */
public class WebSearchTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private static final String SEARCH_URL = "https://qianfan.baidubce.com/v2/ai_search/web_search";
    private static final int TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_TOP_K = 10;

    private final ObjectMapper mapper = JsonMapper.get();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();

    private final String apiKey;

    public WebSearchTool(String apiKey) {
        this.apiKey = apiKey != null ? apiKey : "";
    }

    public static ToolDescriptor descriptor(String apiKey) {
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
                new WebSearchTool(apiKey)
        );
    }

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        String query = (String) arguments.get("query");
        if (query == null || query.isBlank()) {
            return ToolOutcome.failure("缺少 'query' 参数");
        }

        if (apiKey == null || apiKey.isBlank()) {
            return ToolOutcome.failure("百度千帆 API Key 未配置（请检查 agent.yaml 的 web_search.api_key 或环境变量 QIANFAN_API_KEY）");
        }

        // ArgumentSanitizer 已保证类型为 Integer
        int topK = arguments.containsKey("max_results")
                ? Math.min((Integer) arguments.get("max_results"), 20)
                : DEFAULT_TOP_K;

        String siteFilter = (String) arguments.get("site_filter");
        String recencyFilter = (String) arguments.get("recency_filter");

        try {
            log.info("WebSearch(千帆): query='{}', topK={}, site={}, recency={}",
                    query, topK, siteFilter, recencyFilter);

            ObjectNode body = buildRequestBody(query, topK, siteFilter, recencyFilter);
            String responseJson = callApi(body);
            return ToolOutcome.success(parseResponse(responseJson, query));

        } catch (Exception e) {
            log.error("WebSearch(千帆) failed: {}", e.getMessage(), e);
            return ToolOutcome.failure("搜索失败: " + e.getMessage());
        }
    }

    private ObjectNode buildRequestBody(String query, int topK, String siteFilter, String recencyFilter) {
        ObjectNode body = mapper.createObjectNode();

        ArrayNode messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("content", query);
        msg.put("role", "user");

        body.put("search_source", "baidu_search_v2");

        ArrayNode resourceTypeFilter = body.putArray("resource_type_filter");
        ObjectNode webFilter = resourceTypeFilter.addObject();
        webFilter.put("type", "web");
        webFilter.put("top_k", topK);

        if (siteFilter != null && !siteFilter.isBlank()) {
            ObjectNode searchFilter = body.putObject("search_filter");
            ObjectNode match = searchFilter.putObject("match");
            ArrayNode sites = match.putArray("site");
            sites.add(siteFilter);
        }

        if (recencyFilter != null && !recencyFilter.isBlank()) {
            body.put("search_recency_filter", recencyFilter);
        }

        return body;
    }

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
            // 解析错误消息并抛异常，让 execute() 返回 failure 触发熔断
            String errorMsg = "HTTP " + response.statusCode();
            try {
                JsonNode errNode = mapper.readTree(response.body());
                if (errNode.has("message")) {
                    errorMsg = errNode.get("message").asText();
                }
            } catch (Exception ignored) {}
            throw new RuntimeException("千帆 API 错误: " + errorMsg);
        }

        return response.body();
    }

    private String parseResponse(String responseJson, String query) throws Exception {
        JsonNode root = mapper.readTree(responseJson);

        // 检查 API 级别错误（如配额超限等）
        if (root.has("code") && root.has("message")) {
            String code = root.path("code").asText();
            String message = root.path("message").asText();
            log.error("千帆 API 错误: code={}, message={}", code, message);
            throw new RuntimeException("千帆 API 错误 [" + code + "]: " + message);
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
