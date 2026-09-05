package cn.kong.eon.config;

import cn.kong.eon.agent.context.block.CompressionLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;

/**
 * Agent 配置加载器。从 agent.yaml 加载配置，使用 Jackson YAML POJO 绑定。
 * 采用 snake_case 自动映射到驼峰属性，构造完成后不可变。
 * 支持 {@code ${VAR}} / {@code ${VAR:-default}} 环境变量引用。
 */
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    private LlmConfig llm;
    private ContextConfig context;
    private LoopConfig loop;
    private LoopDetectConfig loopDetect;
    private RetryConfig retry;
    private StorageConfig storage;
    private ToolsConfig tools;
    private McpConfig mcp;
    private WebSearchConfig webSearch;
    private ModeConfig mode;
    private BudgetConfig budget;

    /** 从输入流加载配置，执行校验和环境变量解析。 */
    public static AgentConfig load(InputStream yamlStream) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        try {
            AgentConfig config = mapper.readValue(yamlStream, AgentConfig.class);
            if (config == null) {
                throw new IllegalStateException("agent.yaml 加载结果为空，请检查配置文件内容");
            }
            config.validate();
            config.resolveEnvVars();
            return config;
        } catch (Exception e) {
            throw new IllegalStateException("agent.yaml 加载失败: " + e.getMessage(), e);
        }
    }

    /** 从 classpath 加载配置文件。 */
    public static AgentConfig loadFromClasspath(String path) {
        try (InputStream is = AgentConfig.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) throw new IllegalStateException("classpath 中找不到配置: " + path);
            return load(is);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("加载配置失败: " + path, e);
        }
    }

    /** 启动期校验：为缺失的配置节点提供默认值。 */
    private void validate() {
        if (llm == null) llm = new LlmConfig();
        if (context == null) context = new ContextConfig();
        if (context.getCompression() == null) context.setCompression(new ContextConfig.Compression());
        if (loop == null) loop = new LoopConfig();
        if (loopDetect == null) loopDetect = new LoopDetectConfig();
        if (retry == null) retry = new RetryConfig();
        if (storage == null) storage = new StorageConfig();
        if (tools == null) tools = new ToolsConfig();
        if (mcp == null) mcp = new McpConfig();
        if (webSearch == null) webSearch = new WebSearchConfig();
        if (mode == null) mode = new ModeConfig();
        if (budget == null) budget = new BudgetConfig();
    }

    /** 对敏感字段（api_key）执行环境变量解析。 */
    private void resolveEnvVars() {
        if (llm != null) {
            llm.apiKey = resolveEnv(llm.apiKey);
        }
        if (webSearch != null) {
            webSearch.apiKey = resolveEnv(webSearch.apiKey);
        }
    }

    /** 解析环境变量引用，支持 ${VAR} 和 ${VAR:-default} 两种形式。 */
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

        log.warn("环境变量 '{}' 未设置，使用空字符串", envName);
        return "";
    }

    public LlmConfig getLlm() {
        return llm;
    }

    public ContextConfig getContext() {
        return context;
    }

    public LoopConfig getLoop() {
        return loop;
    }

    public LoopDetectConfig getLoopDetect() {
        return loopDetect;
    }

    public RetryConfig getRetry() {
        return retry;
    }

    public StorageConfig getStorage() {
        return storage;
    }

    public ToolsConfig getTools() {
        return tools;
    }

    public McpConfig getMcp() {
        return mcp;
    }

    public WebSearchConfig getWebSearch() {
        return webSearch;
    }

    public boolean isCheckpointEnabled() {
        return mode != null && mode.checkpointEnabled;
    }

    public BudgetConfig getBudget() {
        return budget;
    }

    public void setLlm(LlmConfig llm) {
        this.llm = llm;
    }

    public void setContext(ContextConfig context) {
        this.context = context;
    }

    public void setLoop(LoopConfig loop) {
        this.loop = loop;
    }

    public void setLoopDetect(LoopDetectConfig loopDetect) {
        this.loopDetect = loopDetect;
    }

    public void setRetry(RetryConfig retry) {
        this.retry = retry;
    }

    public void setStorage(StorageConfig storage) {
        this.storage = storage;
    }

    public void setTools(ToolsConfig tools) {
        this.tools = tools;
    }

    public void setMcp(McpConfig mcp) {
        this.mcp = mcp;
    }

    public void setWebSearch(WebSearchConfig webSearch) {
        this.webSearch = webSearch;
    }

    public void setMode(ModeConfig mode) {
        this.mode = mode;
    }

    public void setBudget(BudgetConfig budget) {
        this.budget = budget;
    }

    /** 运行模式配置。 */
    public static class ModeConfig {
        private boolean checkpointEnabled = true;

        public boolean isCheckpointEnabled() {
            return checkpointEnabled;
        }

        public void setCheckpointEnabled(boolean checkpointEnabled) {
            this.checkpointEnabled = checkpointEnabled;
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
    }

    public static class ContextConfig {
        private int maxTokens = 200000;
        private String systemPromptPath = "prompts/system_prompt.md";
        private int summarizeMaxInputChars = 80000;
        private int snipKeepChars = 4000;
        private int summarizeMaxOutputChars = 30000;
        private Compression compression = new Compression();

        /**
         * 压缩机制配置。各项含义见
         * {@link cn.kong.eon.agent.context.policy.CompressionPolicy}，取值约束在那里统一校验。
         */
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
            /** 尾部保护区块数：从最近一次用户输入向上延伸的块数，此区间不参与任何档位 */
            private int tailGuardBlocks = 12;
            /** 参数块骨架化的最小字符数，短参数骨架化反而更长 */
            private int offloadMinChars = 2000;

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

            public int getOffloadMinChars() {
                return offloadMinChars;
            }

            public void setOffloadMinChars(int v) {
                this.offloadMinChars = v;
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

    /** 预算配置。max_tokens 为会话累计 token 上限，threshold 为注入收尾提示的阈值比例。 */
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

        /**
         * Jackson 反序列化 {@code Map<String, McpServerConfig>} 时不会把 map 的 key
         * 注入 value 对象的 key 字段，这里在赋值时补写，
         * 使 {@link McpServerConfig#getKey()} 能拿到 yaml 中配置的服务名。
         */
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

    /**
     * web_search 配置。api_key 之外的三项是工具参数的缺省值：
     * LLM 调用显式传参时以参数为准，未传时回退到这里的配置。
     */
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
