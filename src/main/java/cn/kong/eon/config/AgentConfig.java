package cn.kong.eon.config;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/**
 * Agent 配置加载器。
 * 从 agent.yaml 加载配置，提供类型安全的访问方法。
 */
public class AgentConfig {

    private final Map<String, Object> raw;

    // 子配置
    private final LlmConfig llm;
    private final ContextConfig context;
    private final LoopConfig loop;
    private final LoopDetectConfig loopDetect;
    private final RetryConfig retry;
    private final StorageConfig storage;
    private final ToolsConfig tools;
    private final McpConfig mcp;
    private final WebSearchConfig webSearch;
    private final ModeConfig mode;
    private final BudgetConfig budget;

    public AgentConfig(Map<String, Object> raw) {
        this.raw = raw;
        this.llm = new LlmConfig(getMap("llm"));
        this.context = new ContextConfig(getMap("context"));
        this.loop = new LoopConfig(getMap("loop"));
        this.loopDetect = new LoopDetectConfig(getMap("loop_detect"));
        this.retry = new RetryConfig(getMap("retry"));
        this.storage = new StorageConfig(getMap("storage"));
        this.tools = new ToolsConfig(getMap("tools"));
        this.mcp = new McpConfig(getMap("mcp"));
        this.webSearch = new WebSearchConfig(getMap("web_search"));
        this.mode = new ModeConfig(getMap("mode"));
        this.budget = new BudgetConfig(getMap("budget"));
    }

    public static AgentConfig load(InputStream yamlStream) {
        Yaml yaml = new Yaml();
        Map<String, Object> data = yaml.load(yamlStream);
        return new AgentConfig(data);
    }

    public static AgentConfig loadFromClasspath(String path) {
        try (InputStream is = AgentConfig.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) throw new RuntimeException("Config not found: " + path);
            return load(is);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(String key) {
        Object v = raw.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    public LlmConfig getLlm() { return llm; }
    public ContextConfig getContext() { return context; }
    public LoopConfig getLoop() { return loop; }
    public LoopDetectConfig getLoopDetect() { return loopDetect; }
    public RetryConfig getRetry() { return retry; }
    public StorageConfig getStorage() { return storage; }
    public ToolsConfig getTools() { return tools; }
    public McpConfig getMcp() { return mcp; }
    public WebSearchConfig getWebSearch() { return webSearch; }
    public ModeConfig getMode() { return mode; }
    public BudgetConfig getBudget() { return budget; }

    // ===== 子配置类 =====

    public static class LlmConfig {
        public final String provider;
        public final String baseUrl;
        public final String apiKey;
        public final String modelName;
        public final double temperature;
        public final int timeout;
        public final int maxTokens;

        public LlmConfig(Map<String, Object> m) {
            this.provider = (String) m.getOrDefault("provider", "deepseek");
            this.baseUrl = (String) m.getOrDefault("base_url", "https://api.deepseek.com/v1");
            this.apiKey = resolveEnv((String) m.getOrDefault("api_key", ""));
            this.modelName = (String) m.getOrDefault("model_name", "deepseek-chat");
            this.temperature = ((Number) m.getOrDefault("temperature", 0.0)).doubleValue();
            this.timeout = ((Number) m.getOrDefault("timeout", 120)).intValue();
            this.maxTokens = ((Number) m.getOrDefault("max_tokens", 4096)).intValue();
        }

        private static String resolveEnv(String value) {
            if (value == null) return "";
            if (value.startsWith("${") && value.endsWith("}")) {
                String envName = value.substring(2, value.length() - 1);
                String envValue = System.getenv(envName);
                return envValue != null ? envValue : "";
            }
            return value;
        }
    }

    public static class ContextConfig {
        public final int maxTokens;
        public final double budgetRatio;
        public final String systemPromptPath;
        public final int pinnedMaxTokens;
        public final int insightsMaxItems;
        public final int insightsMaxChars;
        public final int tailGuardMinTokens;
        public final int tailGuardMinTurns;
        public final double snipThreshold;
        public final double pruneThreshold;
        public final double summarizeThreshold;
        public final int snipKeepChars;
        public final int pruneKeepChars;
        public final int summaryTriggerMessages;

        public ContextConfig(Map<String, Object> m) {
            this.maxTokens = ((Number) m.getOrDefault("max_tokens", 120000)).intValue();
            this.budgetRatio = ((Number) m.getOrDefault("budget_ratio", 0.7)).doubleValue();
            this.systemPromptPath = (String) m.getOrDefault("system_prompt_path", "prompts/system_prompt.md");
            this.pinnedMaxTokens = ((Number) m.getOrDefault("pinned_max_tokens", 2048)).intValue();
            Map<String, Object> insights = getSubMap(m, "insights");
            this.insightsMaxItems = ((Number) insights.getOrDefault("max_items", 40)).intValue();
            this.insightsMaxChars = ((Number) insights.getOrDefault("max_chars", 8000)).intValue();
            Map<String, Object> tail = getSubMap(m, "tail_guard");
            this.tailGuardMinTokens = ((Number) tail.getOrDefault("min_tokens", 8000)).intValue();
            this.tailGuardMinTurns = ((Number) tail.getOrDefault("min_turns", 3)).intValue();
            Map<String, Object> comp = getSubMap(m, "compression");
            this.snipThreshold = ((Number) comp.getOrDefault("snip_threshold", 0.65)).doubleValue();
            this.pruneThreshold = ((Number) comp.getOrDefault("prune_threshold", 0.82)).doubleValue();
            this.summarizeThreshold = ((Number) comp.getOrDefault("summarize_threshold", 0.95)).doubleValue();
            this.snipKeepChars = ((Number) comp.getOrDefault("snip_keep_chars", 80)).intValue();
            this.pruneKeepChars = ((Number) comp.getOrDefault("prune_keep_chars", 30)).intValue();
            this.summaryTriggerMessages = ((Number) comp.getOrDefault("summary_trigger_messages", 16)).intValue();
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> getSubMap(Map<String, Object> m, String key) {
            Object v = m.get(key);
            return v instanceof Map ? (Map<String, Object>) v : Map.of();
        }

        public int getMaxTokens() { return maxTokens; }
        public double getBudgetRatio() { return budgetRatio; }
        public String getSystemPromptPath() { return systemPromptPath; }
        public int getPinnedMaxTokens() { return pinnedMaxTokens; }
        public int getInsightsMaxItems() { return insightsMaxItems; }
        public int getInsightsMaxChars() { return insightsMaxChars; }
        public int getTailGuardMinTokens() { return tailGuardMinTokens; }
        public int getTailGuardMinTurns() { return tailGuardMinTurns; }
        public double getSnipThreshold() { return snipThreshold; }
        public double getPruneThreshold() { return pruneThreshold; }
        public double getSummarizeThreshold() { return summarizeThreshold; }
        public int getSnipKeepChars() { return snipKeepChars; }
        public int getPruneKeepChars() { return pruneKeepChars; }
        public int getSummaryTriggerMessages() { return summaryTriggerMessages; }
    }

    public static class LoopConfig {
        public final int maxSteps;
        public final int absoluteMaxSteps;

        public LoopConfig(Map<String, Object> m) {
            this.maxSteps = ((Number) m.getOrDefault("max_steps", 30)).intValue();
            this.absoluteMaxSteps = ((Number) m.getOrDefault("absolute_max_steps", 160)).intValue();
        }

        public int getMaxSteps() { return maxSteps; }
        public int getAbsoluteMaxSteps() { return absoluteMaxSteps; }
    }

    /**
     * 会话预算配置。
     * 独立于上下文窗口大小，控制整个会话的累计 token 消耗。
     */
    public static class BudgetConfig {
        public final int maxTokens;
        public final double softThreshold;
        public final double hardThreshold;
        public final int graceSteps;

        public BudgetConfig(Map<String, Object> m) {
            this.maxTokens = ((Number) m.getOrDefault("max_tokens", 500000)).intValue();
            this.softThreshold = ((Number) m.getOrDefault("soft_threshold", 0.75)).doubleValue();
            this.hardThreshold = ((Number) m.getOrDefault("hard_threshold", 1.0)).doubleValue();
            this.graceSteps = ((Number) m.getOrDefault("grace_steps", 3)).intValue();
        }

        public int getMaxTokens() { return maxTokens; }
        public double getSoftThreshold() { return softThreshold; }
        public double getHardThreshold() { return hardThreshold; }
        public int getGraceSteps() { return graceSteps; }
    }

    public static class LoopDetectConfig {
        public final int repeatWarn;
        public final int repeatStop;
        public final int noProgressSteps;
        public final int failureWarn;
        public final int failureStop;

        public LoopDetectConfig(Map<String, Object> m) {
            this.repeatWarn = ((Number) m.getOrDefault("repeat_warn", 3)).intValue();
            this.repeatStop = ((Number) m.getOrDefault("repeat_stop", 5)).intValue();
            this.noProgressSteps = ((Number) m.getOrDefault("no_progress_steps", 6)).intValue();
            this.failureWarn = ((Number) m.getOrDefault("failure_warn", 3)).intValue();
            this.failureStop = ((Number) m.getOrDefault("failure_stop", 5)).intValue();
        }

        public int getRepeatWarn() { return repeatWarn; }
        public int getRepeatStop() { return repeatStop; }
        public int getNoProgressSteps() { return noProgressSteps; }
        public int getFailureWarn() { return failureWarn; }
        public int getFailureStop() { return failureStop; }
    }

    public static class RetryConfig {
        public final int attempts;
        public final long minDelayMs;
        public final long maxDelayMs;
        public final double jitter;

        public RetryConfig(Map<String, Object> m) {
            this.attempts = ((Number) m.getOrDefault("attempts", 3)).intValue();
            this.minDelayMs = ((Number) m.getOrDefault("min_delay_ms", 500)).longValue();
            this.maxDelayMs = ((Number) m.getOrDefault("max_delay_ms", 5000)).longValue();
            this.jitter = ((Number) m.getOrDefault("jitter", 0.2)).doubleValue();
        }

        public int getAttempts() { return attempts; }
        public long getMinDelayMs() { return minDelayMs; }
        public long getMaxDelayMs() { return maxDelayMs; }
        public double getJitter() { return jitter; }
    }

    public static class StorageConfig {
        public final String baseDir;

        public StorageConfig(Map<String, Object> m) {
            this.baseDir = (String) m.getOrDefault("base_dir", "./data");
        }
    }

    public static class ToolsConfig {
        public final Set<String> whitelist;
        public final Set<String> destructive;
        public final Set<String> readonly;

        @SuppressWarnings("unchecked")
        public ToolsConfig(Map<String, Object> m) {
            this.whitelist = new LinkedHashSet<>((List<String>) m.getOrDefault("whitelist", List.of()));
            this.destructive = new LinkedHashSet<>((List<String>) m.getOrDefault("destructive", List.of()));
            this.readonly = new LinkedHashSet<>((List<String>) m.getOrDefault("readonly", List.of()));
        }
    }

    /**
     * MCP 服务配置。
     * 支持多个 MCP 服务，每个服务有 url / enabled / permission 三个字段。
     */
    public static class McpConfig {
        public final Map<String, McpServerConfig> servers;

        @SuppressWarnings("unchecked")
        public McpConfig(Map<String, Object> m) {
            Map<String, Object> serversRaw = (Map<String, Object>) m.getOrDefault("servers", Map.of());
            Map<String, McpServerConfig> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : serversRaw.entrySet()) {
                String key = entry.getKey();
                if (entry.getValue() instanceof Map<?, ?> serverMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sm = (Map<String, Object>) serverMap;
                    result.put(key, new McpServerConfig(key, sm));
                }
            }
            this.servers = Collections.unmodifiableMap(result);
        }

        public List<McpServerConfig> getEnabledServers() {
            return servers.values().stream().filter(s -> s.enabled).toList();
        }
    }

    public static class McpServerConfig {
        public final String key;
        public final String url;
        public final boolean enabled;
        public final String permission;  // READONLY / RESTRICTED_WRITE / DESTRUCTIVE

        public McpServerConfig(String key, Map<String, Object> m) {
            this.key = key;
            this.url = (String) m.getOrDefault("url", "");
            this.enabled = (boolean) m.getOrDefault("enabled", true);
            this.permission = (String) m.getOrDefault("permission", "READONLY");
        }
    }

    /**
     * 百度千帆 AI Search 配置。
     */
    public static class WebSearchConfig {
        public final String apiKey;
        public final String searchSource;
        public final int topK;
        public final String recencyFilter;  // day/week/month/year/null

        public WebSearchConfig(Map<String, Object> m) {
            this.apiKey = resolveEnv((String) m.getOrDefault("api_key", ""));
            this.searchSource = (String) m.getOrDefault("search_source", "baidu_search_v2");
            this.topK = ((Number) m.getOrDefault("top_k", 10)).intValue();
            this.recencyFilter = (String) m.getOrDefault("recency_filter", "year");
        }

        private static String resolveEnv(String value) {
            if (value == null) return "";
            if (value.startsWith("${") && value.endsWith("}")) {
                String envName = value.substring(2, value.length() - 1);
                String envValue = System.getenv(envName);
                return envValue != null ? envValue : "";
            }
            return value;
        }
    }

    /**
     * 模式配置。
     * 控制运行时行为。
     */
    public static class ModeConfig {
        public final boolean checkpointEnabled;   // 是否启用 Checkpoint（默认 false）

        public ModeConfig(Map<String, Object> m) {
            this.checkpointEnabled = (boolean) m.getOrDefault("checkpoint_enabled", false);
        }
    }
}
