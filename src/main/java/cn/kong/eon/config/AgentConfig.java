package cn.kong.eon.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;

/**
 * Agent 配置加载器。从 agent.yaml 加载配置，使用 Jackson YAML POJO 绑定。
 * <p>
 * 采用 {@link PropertyNamingStrategies#SNAKE_CASE} 自动将 YAML snake_case key 映射到 Java 驼峰属性，
 * 消除手动 Map 强转和类型不安全访问。
 * 所有嵌套配置类使用无参构造 + setter，由 Jackson 自动注入；
 * 对外通过 getter 暴露，构造完成后不可变。
 * <p>
 * 环境变量引用 {@code ${VAR}} / {@code ${VAR:-default}} 在加载后对敏感字段（api_key）执行。
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
    private CompressionConfig compression;
    private MemoryConfig memory;

    // ===== 加载入口 =====

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

    /**
     * 启动期校验：确保关键配置非 null，为缺失的节点提供默认值。
     */
    private void validate() {
        if (llm == null) llm = new LlmConfig();
        if (context == null) context = new ContextConfig();
        if (loop == null) loop = new LoopConfig();
        if (loopDetect == null) loopDetect = new LoopDetectConfig();
        if (retry == null) retry = new RetryConfig();
        if (storage == null) storage = new StorageConfig();
        if (tools == null) tools = new ToolsConfig();
        if (mcp == null) mcp = new McpConfig();
        if (webSearch == null) webSearch = new WebSearchConfig();
        if (mode == null) mode = new ModeConfig();
        if (budget == null) budget = new BudgetConfig();
        if (compression == null) compression = new CompressionConfig();
        if (memory == null) memory = new MemoryConfig();
    }

    /**
     * 对敏感字段执行环境变量解析。
     */
    private void resolveEnvVars() {
        if (llm != null) {
            llm.apiKey = resolveEnv(llm.apiKey);
        }
        if (webSearch != null) {
            webSearch.apiKey = resolveEnv(webSearch.apiKey);
        }
    }

    /**
     * 解析环境变量引用。支持 {@code ${VAR}} 和 {@code ${VAR:-default}}。
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

        log.warn("环境变量 '{}' 未设置，使用空字符串", envName);
        return "";
    }

    // ===== Getter =====

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

    public int getSummarizeTurns() {
        return compression != null ? compression.summarizeTurns : 4;
    }

    public MemoryConfig getMemory() {
        return memory;
    }

    // ===== Setter（供 Jackson 注入） =====

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

    public void setCompression(CompressionConfig compression) {
        this.compression = compression;
    }

    public void setMemory(MemoryConfig memory) {
        this.memory = memory;
    }

    // ===== 嵌套配置类 =====

    /**
     * 顶层 mode 节。
     */
    public static class ModeConfig {
        private boolean checkpointEnabled = false;

        public boolean isCheckpointEnabled() {
            return checkpointEnabled;
        }

        public void setCheckpointEnabled(boolean checkpointEnabled) {
            this.checkpointEnabled = checkpointEnabled;
        }
    }

    /**
     * 顶层 compression 节。
     */
    public static class CompressionConfig {
        private int summarizeTurns = 4;

        public int getSummarizeTurns() {
            return summarizeTurns;
        }

        public void setSummarizeTurns(int summarizeTurns) {
            this.summarizeTurns = summarizeTurns;
        }
    }

    /**
     * 顶层 memory 节。
     */
    public static class MemoryConfig {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class LlmConfig {
        private String provider = "deepseek";
        private String baseUrl = "https://api.deepseek.com/v1";
        private String apiKey = "";
        private String modelName = "deepseek-chat";
        private double temperature = 0.0;
        private int timeout = 120;
        private int maxTokens = 4096;

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
        private int maxTokens = 120000;
        private double budgetRatio = 0.7;
        private String systemPromptPath = "prompts/system_prompt.md";
        private int summarizeMaxInputChars = 50000;
        private Compression compression;

        // 压缩算法内部约束（可配置，从 agent.yaml context 节读取）
        private int snipKeepChars = 2000;
        private int summarizeMaxOutputChars = 2000;
        private int tailGuardMinTurns = 3;

        public static class Compression {
            private double snipThreshold = 0.65;
            private double pruneThreshold = 0.82;
            private double summarizeThreshold = 0.95;

            public double getSnipThreshold() {
                return snipThreshold;
            }

            public void setSnipThreshold(double v) {
                this.snipThreshold = v;
            }

            public double getPruneThreshold() {
                return pruneThreshold;
            }

            public void setPruneThreshold(double v) {
                this.pruneThreshold = v;
            }

            public double getSummarizeThreshold() {
                return summarizeThreshold;
            }

            public void setSummarizeThreshold(double v) {
                this.summarizeThreshold = v;
            }
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int v) {
            this.maxTokens = v;
        }

        public double getBudgetRatio() {
            return budgetRatio;
        }

        public void setBudgetRatio(double v) {
            this.budgetRatio = v;
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

        public Compression getCompression() {
            return compression;
        }

        public void setCompression(Compression v) {
            this.compression = v;
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

        public int getTailGuardMinTurns() {
            return tailGuardMinTurns;
        }

        public void setTailGuardMinTurns(int v) {
            this.tailGuardMinTurns = v;
        }
    }

    public static class LoopConfig {
        private int maxSteps = 30;
        private int absoluteMaxSteps = 160;

        public int getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(int v) {
            this.maxSteps = v;
        }

        public int getAbsoluteMaxSteps() {
            return absoluteMaxSteps;
        }

        public void setAbsoluteMaxSteps(int v) {
            this.absoluteMaxSteps = v;
        }
    }

    public static class BudgetConfig {
        private int maxTokens = 500000;
        private double softThreshold = 0.75;
        private double hardThreshold = 1.0;
        private int graceSteps = 3;

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int v) {
            this.maxTokens = v;
        }

        public double getSoftThreshold() {
            return softThreshold;
        }

        public void setSoftThreshold(double v) {
            this.softThreshold = v;
        }

        public double getHardThreshold() {
            return hardThreshold;
        }

        public void setHardThreshold(double v) {
            this.hardThreshold = v;
        }

        public int getGraceSteps() {
            return graceSteps;
        }

        public void setGraceSteps(int v) {
            this.graceSteps = v;
        }
    }

    public static class LoopDetectConfig {
        private int repeatWarn = 3;
        private int repeatStop = 5;
        private int noProgressSteps = 6;
        private int failureWarn = 3;
        private int failureStop = 5;
        private int stopGraceSteps = 2;

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

        public int getStopGraceSteps() {
            return stopGraceSteps;
        }

        public void setStopGraceSteps(int v) {
            this.stopGraceSteps = v;
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
        private Set<String> destructive = new LinkedHashSet<>();
        private Set<String> readonly = new LinkedHashSet<>();
        private boolean sandboxEnabled = true;
        private int parallelism = 4;
        private int httpConnectTimeoutSeconds = 30;
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

        public Set<String> getDestructive() {
            return destructive;
        }

        public void setDestructive(Set<String> v) {
            this.destructive = v;
        }

        public Set<String> getReadonly() {
            return readonly;
        }

        public void setReadonly(Set<String> v) {
            this.readonly = v;
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

        public int getHttpConnectTimeoutSeconds() {
            return httpConnectTimeoutSeconds;
        }

        public void setHttpConnectTimeoutSeconds(int v) {
            this.httpConnectTimeoutSeconds = v;
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

        public void setServers(Map<String, McpServerConfig> v) {
            this.servers = v;
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

    public static class WebSearchConfig {
        private String apiKey = "";
        private String searchSource = "baidu_search_v2";
        private int topK = 10;
        private String recencyFilter = "year";

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
