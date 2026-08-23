package cn.kong.eon.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/** Agent 配置加载器。从 agent.yaml 加载配置，提供类型安全的访问方法。 */
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    private final Map<String, Object> raw;  // 原始 YAML 数据

    private final LlmConfig llm;            // LLM 配置
    private final ContextConfig context;    // 上下文配置
    private final LoopConfig loop;          // 循环配置
    private final LoopDetectConfig loopDetect; // 循环检测配置
    private final RetryConfig retry;        // 重试配置
    private final StorageConfig storage;    // 存储配置
    private final ToolsConfig tools;        // 工具配置
    private final McpConfig mcp;            // MCP 配置
    private final WebSearchConfig webSearch; // 搜索配置
    private final boolean checkpointEnabled; // 是否启用 Checkpoint
    private final BudgetConfig budget;      // 预算配置
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
        this.checkpointEnabled = (boolean) mode.getOrDefault("checkpoint_enabled", false);
        this.budget = new BudgetConfig(getMap("budget"));
        Map<String, Object> comp = getMap("compression");
        this.summarizeTurns = ((Number) comp.getOrDefault("summarize_turns", 4)).intValue();
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

    // ===== 子配置类 =====

    public static class LlmConfig {
        public final String provider;       // 提供商标识
        public final String baseUrl;        // API 基础地址
        public final String apiKey;         // API Key
        public final String modelName;      // 模型名称
        public final double temperature;    // 采样温度
        public final int timeout;           // 请求超时（秒）
        public final int maxTokens;         // 单次响应最大输出 token

        public LlmConfig(Map<String, Object> m) {
            this.provider = (String) m.getOrDefault("provider", "deepseek");
            this.baseUrl = (String) m.getOrDefault("base_url", "https://api.deepseek.com/v1");
            this.apiKey = AgentConfig.resolveEnv((String) m.getOrDefault("api_key", ""));
            this.modelName = (String) m.getOrDefault("model_name", "deepseek-chat");
            this.temperature = ((Number) m.getOrDefault("temperature", 0.0)).doubleValue();
            this.timeout = ((Number) m.getOrDefault("timeout", 120)).intValue();
            this.maxTokens = ((Number) m.getOrDefault("max_tokens", 4096)).intValue();
        }
    }

    public static class ContextConfig {
        // 高频配置项（从 yaml 读取）
        public final int maxTokens;           // 上下文窗口大小
        public final double budgetRatio;      // 输入 token 占比上限
        public final String systemPromptPath; // 系统提示词路径
        public final double snipThreshold;    // 截短水位
        public final double pruneThreshold;   // 占位符水位
        public final double summarizeThreshold; // 摘要水位

        // 固定常量（不从 yaml 读取）
        public static final int PINNED_MAX_TOKENS = 2048;          // Navigator 最大 token
        public static final int TAIL_GUARD_MIN_TOKENS = 8000;      // 尾部保护最小 token
        public static final int TAIL_GUARD_MIN_TURNS = 3;          // 尾部保护最小轮次
        public static final int SNIP_KEEP_CHARS = 80;              // Snip 保留字符数
        public static final int PRUNE_KEEP_CHARS = 30;             // Prune 保留字符数
        public static final int SUMMARY_TRIGGER_MESSAGES = 16;     // 摘要触发消息数
        public static final int SUMMARIZE_MAX_INPUT_CHARS = 50000; // 摘要输入上限
        public static final int SUMMARIZE_MAX_OUTPUT_CHARS = 2000; // 摘要输出上限

        public ContextConfig(Map<String, Object> m) {
            this.maxTokens = ((Number) m.getOrDefault("max_tokens", 120000)).intValue();
            this.budgetRatio = ((Number) m.getOrDefault("budget_ratio", 0.7)).doubleValue();
            this.systemPromptPath = (String) m.getOrDefault("system_prompt_path", "prompts/system_prompt.md");
            Map<String, Object> comp = getSubMap(m, "compression");
            this.snipThreshold = ((Number) comp.getOrDefault("snip_threshold", 0.65)).doubleValue();
            this.pruneThreshold = ((Number) comp.getOrDefault("prune_threshold", 0.82)).doubleValue();
            this.summarizeThreshold = ((Number) comp.getOrDefault("summarize_threshold", 0.95)).doubleValue();
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
    }

    public static class LoopConfig {
        public final int maxSteps;           // 正常模式最大步数
        public final int absoluteMaxSteps;   // stop 期间绝对上限

        public LoopConfig(Map<String, Object> m) {
            this.maxSteps = ((Number) m.getOrDefault("max_steps", 30)).intValue();
            this.absoluteMaxSteps = ((Number) m.getOrDefault("absolute_max_steps", 160)).intValue();
        }

        public int getMaxSteps() { return maxSteps; }
        public int getAbsoluteMaxSteps() { return absoluteMaxSteps; }
    }

    /** 会话级 Token 预算配置。 */
    public static class BudgetConfig {
        public final int maxTokens;          // 累计 token 上限
        public final double softThreshold;   // 软阈值：注入收尾 nudge
        public final double hardThreshold;   // 硬阈值：触发优雅停止
        public final int graceSteps;         // 触发后额外轮次

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
        public final int repeatWarn;         // 重复调用告警阈值
        public final int repeatStop;         // 重复调用停止阈值
        public final int noProgressSteps;    // 无进展告警步数
        public final int failureWarn;        // 连续失败告警阈值
        public final int failureStop;        // 连续失败停止阈值

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
        public final int attempts;           // 最大重试次数
        public final long minDelayMs;        // 退避起始延迟
        public final long maxDelayMs;        // 退避最大延迟
        public final double jitter;          // 抖动系数

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
        public final String baseDir;         // 会话数据根目录

        public StorageConfig(Map<String, Object> m) {
            this.baseDir = (String) m.getOrDefault("base_dir", "./data");
        }
    }

    public static class ToolsConfig {
        public final Set<String> whitelist;  // 允许注册的本地工具
        public final Set<String> destructive; // 破坏性工具
        public final Set<String> readonly;   // 只读工具
        public final boolean sandboxEnabled;          // 路径沙箱开关
        public final int parallelism;                 // 并行工具执行线程数

        @SuppressWarnings("unchecked")
        public ToolsConfig(Map<String, Object> m) {
            this.whitelist = new LinkedHashSet<>((List<String>) m.getOrDefault("whitelist", List.of()));
            this.destructive = new LinkedHashSet<>((List<String>) m.getOrDefault("destructive", List.of()));
            this.readonly = new LinkedHashSet<>((List<String>) m.getOrDefault("readonly", List.of()));
            this.sandboxEnabled = (boolean) m.getOrDefault("sandbox_enabled", true);
            this.parallelism = ((Number) m.getOrDefault("parallelism", 4)).intValue();
        }
    }

    /** MCP 服务配置，支持多服务。 */
    public static class McpConfig {
        public final Map<String, McpServerConfig> servers;  // 服务列表

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
        public final String key;             // 服务标识
        public final String url;             // 服务端点
        public final boolean enabled;        // 是否启用
        public final String permission;     // 默认权限

        public McpServerConfig(String key, Map<String, Object> m) {
            this.key = key;
            this.url = (String) m.getOrDefault("url", "");
            this.enabled = (boolean) m.getOrDefault("enabled", true);
            this.permission = (String) m.getOrDefault("permission", "READONLY");
        }
    }

    /** 百度千帆 AI Search 配置。 */
    public static class WebSearchConfig {
        public final String apiKey;          // API Key
        public final String searchSource;    // 搜索源
        public final int topK;               // 返回结果数
        public final String recencyFilter;   // 时间过滤

        public WebSearchConfig(Map<String, Object> m) {
            this.apiKey = AgentConfig.resolveEnv((String) m.getOrDefault("api_key", ""));
            this.searchSource = (String) m.getOrDefault("search_source", "baidu_search_v2");
            this.topK = ((Number) m.getOrDefault("top_k", 10)).intValue();
            this.recencyFilter = (String) m.getOrDefault("recency_filter", "year");
        }
    }

}
