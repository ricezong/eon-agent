package cn.kong.eon.config;

import cn.kong.eon.agent.context.block.CompressionLevel;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.*;

/**
 * Agent 配置。通过 Spring Boot {@code @ConfigurationProperties(prefix = "eon")} 自动绑定
 * application.yml 中的 {@code eon.*} 配置项。
 * <p>
 * 环境变量引用 {@code ${VAR}} / {@code ${VAR:-default}} 由 Spring Boot 原生占位符机制处理。
 */
@ConfigurationProperties(prefix = "eon")
public class AgentConfig {

    private LlmConfig llm = new LlmConfig();
    private ContextConfig context = new ContextConfig();
    private LoopConfig loop = new LoopConfig();
    private LoopDetectConfig loopDetect = new LoopDetectConfig();
    private RetryConfig retry = new RetryConfig();
    private StorageConfig storage = new StorageConfig();
    private ToolsConfig tools = new ToolsConfig();
    private McpConfig mcp = new McpConfig();
    private WebSearchConfig webSearch = new WebSearchConfig();
    private ModeConfig mode = new ModeConfig();
    private BudgetConfig budget = new BudgetConfig();

    public LlmConfig getLlm() {
        return llm;
    }

    public void setLlm(LlmConfig llm) {
        this.llm = llm;
    }

    public ContextConfig getContext() {
        return context;
    }

    public void setContext(ContextConfig context) {
        this.context = context;
    }

    public LoopConfig getLoop() {
        return loop;
    }

    public void setLoop(LoopConfig loop) {
        this.loop = loop;
    }

    public LoopDetectConfig getLoopDetect() {
        return loopDetect;
    }

    public void setLoopDetect(LoopDetectConfig loopDetect) {
        this.loopDetect = loopDetect;
    }

    public RetryConfig getRetry() {
        return retry;
    }

    public void setRetry(RetryConfig retry) {
        this.retry = retry;
    }

    public StorageConfig getStorage() {
        return storage;
    }

    public void setStorage(StorageConfig storage) {
        this.storage = storage;
    }

    public ToolsConfig getTools() {
        return tools;
    }

    public void setTools(ToolsConfig tools) {
        this.tools = tools;
    }

    public McpConfig getMcp() {
        return mcp;
    }

    public void setMcp(McpConfig mcp) {
        this.mcp = mcp;
    }

    public WebSearchConfig getWebSearch() {
        return webSearch;
    }

    public void setWebSearch(WebSearchConfig webSearch) {
        this.webSearch = webSearch;
    }

    /** 是否启用会话快照。 */
    public boolean isSnapshotEnabled() {
        return mode != null && mode.snapshotEnabled;
    }

    public ModeConfig getMode() {
        return mode;
    }

    public void setMode(ModeConfig mode) {
        this.mode = mode;
    }

    public BudgetConfig getBudget() {
        return budget;
    }

    public void setBudget(BudgetConfig budget) {
        this.budget = budget;
    }

    // ════════════════════════════════════════════════════════════════════
    //  内部配置类
    // ════════════════════════════════════════════════════════════════════

    /** 运行模式配置。 */
    public static class ModeConfig {
        private boolean snapshotEnabled = true;

        public boolean isSnapshotEnabled() {
            return snapshotEnabled;
        }

        public void setSnapshotEnabled(boolean snapshotEnabled) {
            this.snapshotEnabled = snapshotEnabled;
        }
    }

    public static class LlmConfig {
        private String provider = "mimo";
        private String baseUrl = "https://api.xiaomimimo.com/v1";
        private String apiKey = "";
        private String modelName = "mimo-v2.5";
        private double temperature = 0.7;
        private int timeout = 120;
        private int maxTokens = 12000;
        private boolean streamEnabled = true;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String v) {
            this.provider = v;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String v) {
            this.baseUrl = v;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String v) {
            this.apiKey = v;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String v) {
            this.modelName = v;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double v) {
            this.temperature = v;
        }

        public int getTimeout() {
            return timeout;
        }

        public void setTimeout(int v) {
            this.timeout = v;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int v) {
            this.maxTokens = v;
        }

        public boolean isStreamEnabled() {
            return streamEnabled;
        }

        public void setStreamEnabled(boolean v) {
            this.streamEnabled = v;
        }
    }

    public static class ContextConfig {
        private int maxTokens = 200000;
        private String systemPromptPath = "prompts/system_prompt.md";
        private int summarizeMaxInputChars = 80000;
        private int snipKeepChars = 4000;
        private int summarizeMaxOutputChars = 30000;
        /** 工具结果超过此长度才落盘 */
        private int spillThresholdChars = 12000;
        /** 落盘后块里保留的头尾摘要长度 */
        private int spillKeepChars = 8000;
        private Compression compression = new Compression();

        /** 压缩机制配置，各项含义见 CompressionPolicy。 */
        public static class Compression {
            /** SNIP 档水位下限 */
            private double snipWaterLevel = 0.65;
            /** PRUNE 档水位下限 */
            private double pruneWaterLevel = 0.80;
            /** SUMMARIZE 档水位下限 */
            private double summarizeWaterLevel = 0.92;
            /** 轮数触发周期：轮次序号为其整数倍时命中轮数入口 */
            private int turnInterval = 7;
            /** 轮数入口命中且水位三档均未命中时执行的档位 */
            private CompressionLevel turnLevel = CompressionLevel.SNIP;
            /** 尾部保护区块数：窗口末尾这些块不参与任何档位 */
            private int tailGuardBlocks = 12;
            /** 参数块字段裁剪的最小字符数，短参数裁剪后反而更长 */
            private int argsPruneMinChars = 2000;

            public double getSnipWaterLevel() {
                return snipWaterLevel;
            }

            public void setSnipWaterLevel(double v) {
                this.snipWaterLevel = v;
            }

            public double getPruneWaterLevel() {
                return pruneWaterLevel;
            }

            public void setPruneWaterLevel(double v) {
                this.pruneWaterLevel = v;
            }

            public double getSummarizeWaterLevel() {
                return summarizeWaterLevel;
            }

            public void setSummarizeWaterLevel(double v) {
                this.summarizeWaterLevel = v;
            }

            public int getTurnInterval() {
                return turnInterval;
            }

            public void setTurnInterval(int v) {
                this.turnInterval = v;
            }

            public CompressionLevel getTurnLevel() {
                return turnLevel;
            }

            public void setTurnLevel(CompressionLevel v) {
                this.turnLevel = v;
            }

            public int getTailGuardBlocks() {
                return tailGuardBlocks;
            }

            public void setTailGuardBlocks(int v) {
                this.tailGuardBlocks = v;
            }

            public int getArgsPruneMinChars() {
                return argsPruneMinChars;
            }

            public void setArgsPruneMinChars(int v) {
                this.argsPruneMinChars = v;
            }
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int v) {
            this.maxTokens = v;
        }

        public String getSystemPromptPath() {
            return systemPromptPath;
        }

        public void setSystemPromptPath(String v) {
            this.systemPromptPath = v;
        }

        public int getSummarizeMaxInputChars() {
            return summarizeMaxInputChars;
        }

        public void setSummarizeMaxInputChars(int v) {
            this.summarizeMaxInputChars = v;
        }

        public int getSnipKeepChars() {
            return snipKeepChars;
        }

        public void setSnipKeepChars(int v) {
            this.snipKeepChars = v;
        }

        public int getSummarizeMaxOutputChars() {
            return summarizeMaxOutputChars;
        }

        public void setSummarizeMaxOutputChars(int v) {
            this.summarizeMaxOutputChars = v;
        }

        public int getSpillThresholdChars() {
            return spillThresholdChars;
        }

        public void setSpillThresholdChars(int v) {
            this.spillThresholdChars = v;
        }

        public int getSpillKeepChars() {
            return spillKeepChars;
        }

        public void setSpillKeepChars(int v) {
            this.spillKeepChars = v;
        }

        public Compression getCompression() {
            return compression;
        }

        public void setCompression(Compression v) {
            this.compression = v;
        }
    }

    public static class LoopConfig {
        private int maxSteps = 100;

        public int getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(int v) {
            this.maxSteps = v;
        }
    }

    /** 预算配置：max_tokens 为会话累计上限，threshold 为注入收尾提示的阈值比例。 */
    public static class BudgetConfig {
        private int maxTokens = 2000000;
        private double threshold = 0.75;

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int v) {
            this.maxTokens = v;
        }

        public double getThreshold() {
            return threshold;
        }

        public void setThreshold(double v) {
            this.threshold = v;
        }
    }

    public static class LoopDetectConfig {
        private int repeatWarn = 3;
        private int repeatStop = 5;
        private int noProgressSteps = 6;
        private int failureWarn = 3;
        private int failureStop = 5;
        private int cooldownTurns = 3;

        public int getRepeatWarn() {
            return repeatWarn;
        }

        public void setRepeatWarn(int v) {
            this.repeatWarn = v;
        }

        public int getRepeatStop() {
            return repeatStop;
        }

        public void setRepeatStop(int v) {
            this.repeatStop = v;
        }

        public int getNoProgressSteps() {
            return noProgressSteps;
        }

        public void setNoProgressSteps(int v) {
            this.noProgressSteps = v;
        }

        public int getFailureWarn() {
            return failureWarn;
        }

        public void setFailureWarn(int v) {
            this.failureWarn = v;
        }

        public int getFailureStop() {
            return failureStop;
        }

        public void setFailureStop(int v) {
            this.failureStop = v;
        }

        public int getCooldownTurns() {
            return cooldownTurns;
        }

        public void setCooldownTurns(int v) {
            this.cooldownTurns = v;
        }
    }

    public static class RetryConfig {
        private int attempts = 3;
        private long minDelayMs = 500;
        private long maxDelayMs = 5000;
        private double jitter = 0.2;

        public int getAttempts() {
            return attempts;
        }

        public void setAttempts(int v) {
            this.attempts = v;
        }

        public long getMinDelayMs() {
            return minDelayMs;
        }

        public void setMinDelayMs(long v) {
            this.minDelayMs = v;
        }

        public long getMaxDelayMs() {
            return maxDelayMs;
        }

        public void setMaxDelayMs(long v) {
            this.maxDelayMs = v;
        }

        public double getJitter() {
            return jitter;
        }

        public void setJitter(double v) {
            this.jitter = v;
        }
    }

    public static class StorageConfig {
        private String baseDir = "./data";

        public String getBaseDir() {
            return baseDir;
        }

        public void setBaseDir(String v) {
            this.baseDir = v;
        }
    }

    public static class ToolsConfig {
        private Set<String> whitelist = new LinkedHashSet<>();
        private boolean sandboxEnabled = true;
        private int parallelism = 4;
        /** 破坏性工具是否自动批准，false 时触发 GATE_REJECTED 终止 */
        private boolean autoApproveDestructive = true;
        private WebFetch webFetch;
        private Download download;

        public static class WebFetch {
            private int maxContentLength = 50000;
            private int cacheTtlMinutes = 15;
            private int cacheMaxEntries = 64;

            public int getMaxContentLength() {
                return maxContentLength;
            }

            public void setMaxContentLength(int v) {
                this.maxContentLength = v;
            }

            public int getCacheTtlMinutes() {
                return cacheTtlMinutes;
            }

            public void setCacheTtlMinutes(int v) {
                this.cacheTtlMinutes = v;
            }

            public int getCacheMaxEntries() {
                return cacheMaxEntries;
            }

            public void setCacheMaxEntries(int v) {
                this.cacheMaxEntries = v;
            }
        }

        public static class Download {
            private long maxFileSizeMb = 100;

            public long getMaxFileSizeMb() {
                return maxFileSizeMb;
            }

            public void setMaxFileSizeMb(long v) {
                this.maxFileSizeMb = v;
            }
        }

        public Set<String> getWhitelist() {
            return whitelist;
        }

        public void setWhitelist(Set<String> v) {
            this.whitelist = v;
        }

        public boolean isSandboxEnabled() {
            return sandboxEnabled;
        }

        public void setSandboxEnabled(boolean v) {
            this.sandboxEnabled = v;
        }

        public int getParallelism() {
            return parallelism;
        }

        public void setParallelism(int v) {
            this.parallelism = v;
        }

        public boolean isAutoApproveDestructive() {
            return autoApproveDestructive;
        }

        public void setAutoApproveDestructive(boolean v) {
            this.autoApproveDestructive = v;
        }

        public WebFetch getWebFetch() {
            return webFetch;
        }

        public void setWebFetch(WebFetch v) {
            this.webFetch = v;
        }

        public Download getDownload() {
            return download;
        }

        public void setDownload(Download v) {
            this.download = v;
        }
    }

    public static class McpConfig {
        private Map<String, McpServerConfig> servers = new LinkedHashMap<>();

        public Map<String, McpServerConfig> getServers() {
            return servers;
        }

        /** Spring Boot 绑定时不把 map key 注入 value 的 key 字段，这里补写。 */
        public void setServers(Map<String, McpServerConfig> v) {
            this.servers = v;
            if (v != null) {
                v.forEach((k, s) -> {
                    if (s != null) s.key = k;
                });
            }
        }

        public List<McpServerConfig> getEnabledServers() {
            return servers.values().stream().filter(s -> s.enabled).toList();
        }
    }

    public static class McpServerConfig {
        private String key;
        private String url = "";
        private boolean enabled = true;
        private String permission = "READONLY";

        public String getKey() {
            return key;
        }

        public void setKey(String v) {
            this.key = v;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String v) {
            this.url = v;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean v) {
            this.enabled = v;
        }

        public String getPermission() {
            return permission;
        }

        public void setPermission(String v) {
            this.permission = v;
        }
    }

    /** web_search 配置。除 api_key 外的三项是工具参数缺省值，LLM 显式传参时以参数为准。 */
    public static class WebSearchConfig {
        private String apiKey = "";
        /** 搜索源，直传千帆 API 的 search_source 字段 */
        private String searchSource = "baidu_search_v2";
        /** 工具未传 max_results 时的默认返回条数 */
        private int topK = 10;
        /** 工具未传 recency_filter 时的默认时间过滤，取值 pd/pw/pm/py */
        private String recencyFilter = "py";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String v) {
            this.apiKey = v;
        }

        public String getSearchSource() {
            return searchSource;
        }

        public void setSearchSource(String v) {
            this.searchSource = v;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int v) {
            this.topK = v;
        }

        public String getRecencyFilter() {
            return recencyFilter;
        }

        public void setRecencyFilter(String v) {
            this.recencyFilter = v;
        }
    }
}
