package cn.kong.eon.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/** Agent 配置加载器。从 agent.yaml 加载配置，提供类型安全的访问方法。 */
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    private final Map<String, Object> raw;

    private final LlmConfig llm;
    private final ContextConfig context;
    private final LoopConfig loop;
    private final LoopDetectConfig loopDetect;
    private final RetryConfig retry;
    private final StorageConfig storage;
    private final ToolsConfig tools;
    private final McpConfig mcp;
    private final WebSearchConfig webSearch;
    private final boolean checkpointEnabled;
    private final BudgetConfig budget;
    private final int summarizeTurns;       // 轮数触发摘要阈值

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
        Map<String, Object> mode = getMap("mode");
        this.checkpointEnabled = parseBoolean(mode, "checkpoint_enabled", false, "mode");
        this.budget = new BudgetConfig(getMap("budget"));
        Map<String, Object> comp = getMap("compression");
        this.summarizeTurns = parseInt(comp, "summarize_turns", 4, "compression");
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

    static String parseString(Map<String, Object> m, String key, String fallback, String section) {
        Object v = m.getOrDefault(key, fallback);
        if (v == null) return fallback;
        if (v instanceof String s) return s;
        throw configTypeError(section, key, v, "String");
    }

    static int parseInt(Map<String, Object> m, String key, int fallback, String section) {
        Object v = m.getOrDefault(key, fallback);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.intValue();
        throw configTypeError(section, key, v, "int");
    }

    static long parseLong(Map<String, Object> m, String key, long fallback, String section) {
        Object v = m.getOrDefault(key, fallback);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.longValue();
        throw configTypeError(section, key, v, "long");
    }

    static double parseDouble(Map<String, Object> m, String key, double fallback, String section) {
        Object v = m.getOrDefault(key, fallback);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.doubleValue();
        throw configTypeError(section, key, v, "double");
    }

    static boolean parseBoolean(Map<String, Object> m, String key, boolean fallback, String section) {
        Object v = m.getOrDefault(key, fallback);
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        throw configTypeError(section, key, v, "boolean");
    }

    private static IllegalStateException configTypeError(String section, String key, Object value, String expectedType) {
        String actualType = value == null ? "null" : value.getClass().getSimpleName();
        return new IllegalStateException(String.format(
                "配置类型错误: %s.%s 期望 %s，实际得到 %s (值=%s)。" +
                "请检查 agent.yaml 中该字段的类型是否正确。",
                section, key, expectedType, actualType, value));
    }

    /**
     * 解析环境变量引用。支持 ${VAR} 和 ${VAR:-default}。
     */
    static String resolveEnv(String value) {
        if (value == null) return "";
        if (!value.startsWith("${") || !value.endsWith("}")) return value;

        String inner = value.substring(2, value.length() - 1);
        String envName;
        String defaultValue = null;

        int sepIdx = inner.indexOf(":-");
        if (sepIdx >= 0) {
            envName = inner.substring(0, sepIdx);
            defaultValue = inner.substring(sepIdx + 2);
        } else {
            envName = inner;
        }

        String envValue = System.getenv(envName);
        if (envValue != null) return envValue;
        if (defaultValue != null) return defaultValue;

        log.warn("Environment variable '{}' not set, using empty string", envName);
        return "";
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
    public boolean isCheckpointEnabled() { return checkpointEnabled; }
    public BudgetConfig getBudget() { return budget; }
    public int getSummarizeTurns() { return summarizeTurns; }

    public static class LlmConfig {
        public final String provider;
        public final String baseUrl;
        public final String apiKey;
        public final String modelName;
        public final double temperature;
        public final int timeout;           // 请求超时（秒）
        public final int maxTokens;         // 单次响应最大输出 token

        public LlmConfig(Map<String, Object> m) {
            this.provider = parseString(m, "provider", "deepseek", "llm");
            this.baseUrl = parseString(m, "base_url", "https://api.deepseek.com/v1", "llm");
            this.apiKey = AgentConfig.resolveEnv(parseString(m, "api_key", "", "llm"));
            this.modelName = parseString(m, "model_name", "deepseek-chat", "llm");
            this.temperature = parseDouble(m, "temperature", 0.0, "llm");
            this.timeout = parseInt(m, "timeout", 120, "llm");
            this.maxTokens = parseInt(m, "max_tokens", 4096, "llm");
        }
    }

    public static class ContextConfig {
        public final int maxTokens;           // 上下文窗口大小
        public final double budgetRatio;      // 输入 token 占比上限
        public final String systemPromptPath;
        public final double snipThreshold;    // 截短水位
        public final double pruneThreshold;   // 占位符水位
        public final double summarizeThreshold; // 摘要水位
        public final int summarizeMaxInputChars; // 摘要输入上限

        // 固定常量：压缩算法内部约束，非用户可调参数
        public static final int PINNED_MAX_TOKENS = 2048;          // Navigator 最大 token
        public static final int TAIL_GUARD_MIN_TOKENS = 8000;      // 尾部保护最小 token
        public static final int TAIL_GUARD_MIN_TURNS = 3;          // 尾部保护最小轮次
        public static final int SNIP_KEEP_CHARS = 80;              // Snip 保留字符数
        public static final int PRUNE_KEEP_CHARS = 30;             // Prune 保留字符数
        public static final int SUMMARY_TRIGGER_MESSAGES = 16;     // 摘要触发消息数
        public static final int SUMMARIZE_MAX_OUTPUT_CHARS = 2000; // 摘要输出上限

        public ContextConfig(Map<String, Object> m) {
            this.maxTokens = parseInt(m, "max_tokens", 120000, "context");
            this.budgetRatio = parseDouble(m, "budget_ratio", 0.7, "context");
            this.systemPromptPath = parseString(m, "system_prompt_path", "prompts/system_prompt.md", "context");
            this.summarizeMaxInputChars = parseInt(m, "summarize_max_input_chars", 50000, "context");
            Map<String, Object> comp = getSubMap(m, "compression");
            this.snipThreshold = parseDouble(comp, "snip_threshold", 0.65, "context.compression");
            this.pruneThreshold = parseDouble(comp, "prune_threshold", 0.82, "context.compression");
            this.summarizeThreshold = parseDouble(comp, "summarize_threshold", 0.95, "context.compression");
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> getSubMap(Map<String, Object> m, String key) {
            Object v = m.get(key);
            return v instanceof Map ? (Map<String, Object>) v : Map.of();
        }

        public int getMaxTokens() { return maxTokens; }
        public double getBudgetRatio() { return budgetRatio; }
        public String getSystemPromptPath() { return systemPromptPath; }
        public double getSnipThreshold() { return snipThreshold; }
        public double getPruneThreshold() { return pruneThreshold; }
        public double getSummarizeThreshold() { return summarizeThreshold; }
        public int getSummarizeMaxInputChars() { return summarizeMaxInputChars; }
    }

    public static class LoopConfig {
        public final int maxSteps;           // 正常模式最大步数
        public final int absoluteMaxSteps;   // stop 期间绝对上限


        public LoopConfig(Map<String, Object> m) {
            this.maxSteps = parseInt(m, "max_steps", 30, "loop");
            this.absoluteMaxSteps = parseInt(m, "absolute_max_steps", 160, "loop");
        }

        public int getMaxSteps() { return maxSteps; }
        public int getAbsoluteMaxSteps() { return absoluteMaxSteps; }
    }

    public static class BudgetConfig {
        public final int maxTokens;          // 累计 token 上限
        public final double softThreshold;   // 软阈值：注入收尾 nudge
        public final double hardThreshold;   // 硬阈值：触发优雅停止
        public final int graceSteps;         // 触发后额外轮次

        public BudgetConfig(Map<String, Object> m) {
            this.maxTokens = parseInt(m, "max_tokens", 500000, "budget");
            this.softThreshold = parseDouble(m, "soft_threshold", 0.75, "budget");
            this.hardThreshold = parseDouble(m, "hard_threshold", 1.0, "budget");
            this.graceSteps = parseInt(m, "grace_steps", 3, "budget");
        }

        public int getMaxTokens() { return maxTokens; }
        public double getSoftThreshold() { return softThreshold; }
        public double getHardThreshold() { return hardThreshold; }
        public int getGraceSteps() { return graceSteps; }
    }

    public static class LoopDetectConfig {
        public final int repeatWarn;         // 重复调用告警阈值
        public final int repeatStop;         // 重复调用停止阈值
        public final int noProgressSteps;    // 无进展告警步数
        public final int failureWarn;        // 连续失败告警阈值
        public final int failureStop;        // 连续失败停止阈值
        public final int stopGraceSteps;     // 循环检测/门禁停止后给 LLM 的 grace 步数

        public LoopDetectConfig(Map<String, Object> m) {
            this.repeatWarn = parseInt(m, "repeat_warn", 3, "loop_detect");
            this.repeatStop = parseInt(m, "repeat_stop", 5, "loop_detect");
            this.noProgressSteps = parseInt(m, "no_progress_steps", 6, "loop_detect");
            this.failureWarn = parseInt(m, "failure_warn", 3, "loop_detect");
            this.failureStop = parseInt(m, "failure_stop", 5, "loop_detect");
            this.stopGraceSteps = parseInt(m, "stop_grace_steps", 2, "loop_detect");
        }

        public int getRepeatWarn() { return repeatWarn; }
        public int getRepeatStop() { return repeatStop; }
        public int getNoProgressSteps() { return noProgressSteps; }
        public int getFailureWarn() { return failureWarn; }
        public int getFailureStop() { return failureStop; }
        public int getStopGraceSteps() { return stopGraceSteps; }
    }

    public static class RetryConfig {
        public final int attempts;
        public final long minDelayMs;
        public final long maxDelayMs;
        public final double jitter;

        public RetryConfig(Map<String, Object> m) {
            this.attempts = parseInt(m, "attempts", 3, "retry");
            this.minDelayMs = parseLong(m, "min_delay_ms", 500, "retry");
            this.maxDelayMs = parseLong(m, "max_delay_ms", 5000, "retry");
            this.jitter = parseDouble(m, "jitter", 0.2, "retry");
        }

        public int getAttempts() { return attempts; }
        public long getMinDelayMs() { return minDelayMs; }
        public long getMaxDelayMs() { return maxDelayMs; }
        public double getJitter() { return jitter; }
    }

    public static class StorageConfig {
        public final String baseDir;

        public StorageConfig(Map<String, Object> m) {
            this.baseDir = parseString(m, "base_dir", "./data", "storage");
        }
    }

    public static class ToolsConfig {
        public final Set<String> whitelist;
        public final Set<String> destructive;
        public final Set<String> readonly;
        public final boolean sandboxEnabled;          // 路径沙箱开关
        public final int parallelism;                 // 并行工具执行线程数
        public final int httpConnectTimeoutSeconds;   // 共享 HttpClient 连接超时（秒）
        public final int webFetchMaxContentLength;    // web_fetch 内容截断字符数
        public final int webFetchCacheTtlMinutes;     // web_fetch 缓存 TTL（分钟）
        public final int webFetchCacheMaxEntries;     // web_fetch LRU 缓存上限
        public final long downloadMaxFileSizeMb;      // 下载文件大小上限（MB）
        public final int grepMaxFileSizeMb;           // grep 单文件大小上限（MB）
        public final int grepMaxMatchLines;           // grep 最大匹配行数
        public final int grepMaxOutputChars;          // grep 输出截断字符数

        @SuppressWarnings("unchecked")
        public ToolsConfig(Map<String, Object> m) {
            this.whitelist = new LinkedHashSet<>((List<String>) m.getOrDefault("whitelist", List.of()));
            this.destructive = new LinkedHashSet<>((List<String>) m.getOrDefault("destructive", List.of()));
            this.readonly = new LinkedHashSet<>((List<String>) m.getOrDefault("readonly", List.of()));
            this.sandboxEnabled = parseBoolean(m, "sandbox_enabled", true, "tools");
            this.parallelism = parseInt(m, "parallelism", 4, "tools");
            this.httpConnectTimeoutSeconds = parseInt(m, "http_connect_timeout_seconds", 30, "tools");
            Map<String, Object> webFetch = getSubMap(m, "web_fetch");
            this.webFetchMaxContentLength = parseInt(webFetch, "max_content_length", 50000, "tools.web_fetch");
            this.webFetchCacheTtlMinutes = parseInt(webFetch, "cache_ttl_minutes", 15, "tools.web_fetch");
            this.webFetchCacheMaxEntries = parseInt(webFetch, "cache_max_entries", 64, "tools.web_fetch");
            Map<String, Object> download = getSubMap(m, "download");
            this.downloadMaxFileSizeMb = parseLong(download, "max_file_size_mb", 100, "tools.download");
            Map<String, Object> grep = getSubMap(m, "grep");
            this.grepMaxFileSizeMb = parseInt(grep, "max_file_size_mb", 10, "tools.grep");
            this.grepMaxMatchLines = parseInt(grep, "max_match_lines", 500, "tools.grep");
            this.grepMaxOutputChars = parseInt(grep, "max_output_chars", 50000, "tools.grep");
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> getSubMap(Map<String, Object> m, String key) {
            Object v = m.get(key);
            return v instanceof Map ? (Map<String, Object>) v : Map.of();
        }
    }

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
        public final String permission;

        public McpServerConfig(String key, Map<String, Object> m) {
            this.key = key;
            this.url = parseString(m, "url", "", "mcp.servers." + key);
            this.enabled = parseBoolean(m, "enabled", true, "mcp.servers." + key);
            this.permission = parseString(m, "permission", "READONLY", "mcp.servers." + key);
        }
    }

    public static class WebSearchConfig {
        public final String apiKey;
        public final String searchSource;
        public final int topK;               // 返回结果数
        public final String recencyFilter;   // 时间过滤

        public WebSearchConfig(Map<String, Object> m) {
            this.apiKey = AgentConfig.resolveEnv(parseString(m, "api_key", "", "web_search"));
            this.searchSource = parseString(m, "search_source", "baidu_search_v2", "web_search");
            this.topK = parseInt(m, "top_k", 10, "web_search");
            this.recencyFilter = parseString(m, "recency_filter", "year", "web_search");
        }
    }

}
