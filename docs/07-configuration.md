# 07 · 配置参考

> 全部业务配置集中在 `AgentConfig`（`@ConfigurationProperties(prefix = "eon")`），由 `EonAgentApplication:12` 的 `@EnableConfigurationProperties(AgentConfig.class)` 启用。yml 用 snake_case、配置类用 camelCase，靠 Spring relaxed binding 对接。

## 关键类

| 类 | 路径 | 职责 |
|---|---|---|
| `AgentConfig` | `config/AgentConfig.java` | 13 个嵌套配置组，含全部默认值 |
| `AgentBeans` | `config/AgentBeans.java` | 应用级单例装配：ToolService / CompressionPolicy / systemPrompt / TokenCountEstimator / MCP 连接 |
| `ExecutorConfig` | `config/ExecutorConfig.java` | `sseExecutor` 线程池 |
| `HttpConfig` | `config/HttpConfig.java` | 工具共用的 `HttpClient` |
| `ObjectMapperConfig` | `config/ObjectMapperConfig.java` | Jackson 配置 |
| `SqliteConfig` | `store/db/SqliteConfig.java` | DataSource + PRAGMA + DDL |
| `SessionCacheConfig` | `runtime/cache/SessionCacheConfig.java` | Caffeine 缓存构建 |

---

## 1. 默认值差异：yml vs 配置类

**这是本篇最需要先看的一节。** 配置类里的默认值与 `application.yml` 里的值有 3 处不一致，运行时以 yml 为准，但读代码时容易被字段初始值误导。

| 键 | `AgentConfig` 默认值 | `application.yml` 值 | 说明 |
|---|---|---|---|
| `eon.llm.timeout` | `120`（`AgentConfig.java:159`） | `300`（yml:23） | **相差 2.5 倍**。yml 生效 |
| `eon.storage.base_dir` | `/home/workspace/sessions`（`:543`） | `./home/workspace/sessions`（yml:81） | 绝对路径 vs 相对路径，行为完全不同 |
| `eon.storage.db_path` | `/home/workspace/sessions/eon.db`（`:544`） | `./home/workspace/sessions/eon.db`（yml:82） | 同上 |

另有**整组配置在 yml 中完全缺失**，实际跑的是配置类默认值：

| 缺失的组/键 | 生效默认值 | 影响 |
|---|---|---|
| `eon.interaction.*` | `timeoutSeconds=600`（`:402`）、`sseTimeoutSeconds=1800`（`:405`） | 提问等待与 SSE 超时都不可见于配置文件 |
| `eon.tools.auto_approve_destructive` | `true`（`:635`） | GateHook 只记日志不拦截 |
| `eon.loop_detect.cooldown_turns` | `3`（`:452`） | 熔断冷却轮数 |
| `eon.retry.*` 全部 | `attempts=3` / `minDelayMs=500` / `maxDelayMs=5000` / `jitter=0.2` | yml:74-78 其实有配，值与默认一致 |

---

## 2. `eon.llm` — 模型接入

`AgentConfig.LlmConfig`（`:153-161`），消费方 `llm/LlmClient.java:40-74`。

| 键 | 默认 | yml | 含义与生效位置 |
|---|---|---|---|
| `provider` | `mimo` | `mimo` | 仅用于启动日志（`LlmClient.java:67`、`:71`） |
| `base_url` | `https://api.xiaomimimo.com/v1` | 同 | `OpenAiChatModel.baseUrl` / `OpenAiStreamingChatModel.baseUrl` |
| `api_key` | `""` | `${LLM_API_KEY}` | **环境变量必填**，缺失会导致所有 LLM 调用失败 |
| `model_name` | `mimo-v2.5` | 同 | 两个模型的 `modelName` |
| `temperature` | `0.7` | 同 | 两个模型的 `temperature` |
| `timeout` | **120** | **300** | 两个模型的 `Duration.ofSeconds(...)`。**注意流式路径的实际等待是硬编码 300s**（`LlmClient.java:212`），不读此值 |
| `max_tokens` | `12000` | 同 | ①模型输出上限 ②上下文度量的 `outputReserveTokens`（`AgentEngine.java:275`） |
| `stream_enabled` | `true` | 同 | false 时**流式模型根本不构建**（`LlmClient.java:57-73`），字段为 null |

两个模型都设了 `.returnThinking(true)`（`LlmClient.java:52`、`:65`），这是 `AgentThinking` 事件与账本 `thinking` 字段的来源。

---

## 3. `eon.context` — 上下文与压缩

`AgentConfig.ContextConfig`（`:228-259`），装配方 `config/AgentBeans.java:92-111`。

### 顶层

| 键 | 默认 | yml | 含义与生效位置 |
|---|---|---|---|
| `max_tokens` | `200000` | 同 | 水位分母（`AgentEngine.java:276` → `ContextMetrics.waterLevel()`） |
| `system_prompt_path` | `prompts/system_prompt.md` | 同 | classpath 相对路径，启动时一次性加载（`AgentBeans.java:127-144`） |
| `summarize_max_input_chars` | `80000` | 同 | 摘要单段输入上限（`ContextSummarizer.segment`） |
| `summarize_max_output_chars` | `12000` | 同 | 摘要输出上限，写进 prompt（`ContextSummarizer.java:199`） |
| `snip_keep_chars` | `4000` | 同 | SNIP 档头尾保留字符数（`CompressionSettings.snipKeepChars`） |
| `spill_threshold_chars` | `12000` | 同 | 工具结果落盘阈值（`IngestPipeline.java:49`） |
| `spill_keep_chars` | `8000` | 同 | 落盘后块内保留的头尾摘要长度（`IngestPipeline.java:78`） |

**耦合约束**（yml:43-45 原注释）：

> `summarize_max_output_chars` 必须 ≤ `eon.llm.max_tokens`：超出则模型吐不完被截断，而 `complete()` 丢弃 finishReason 不感知截断，残缺摘要会被当成有效结果存下来并在下一轮继续被压

当前 12000 == 12000，正好卡在上限。调大 `summarize_max_output_chars` 或调小 `max_tokens` 都会导致**静默的信息腐蚀且自我放大**。

### `eon.context.compression` 子树

| 键 | 默认 | yml | 含义 |
|---|---|---|---|
| `snip_water_level` | `0.65` | 同 | 水位 ≥65%（~130K）触发 SNIP |
| `prune_water_level` | `0.80` | 同 | ≥80%（~160K）触发 PRUNE |
| `summarize_water_level` | `0.92` | 同 | ≥92%（~184K）触发 SUMMARIZE |
| `turn_interval` | `7` | 同 | 轮数兜底周期：`turnCount % 7 == 0` |
| `turn_level` | `"SNIP"` | 同 | 兜底档位。**字符串**，非法值记 WARN 回退 SNIP（`AgentBeans.java:113-124`） |
| `tail_guard_blocks` | `12` | 同 | 尾部保护区块数，任何档位都不动 |
| `args_prune_min_chars` | `2000` | 同 | TOOL_ARGS 骨架化的最小字符数（短参数裁剪后反而更长） |

`turn_level` 走字符串而非枚举，是为了让非法配置**降级而非启动失败**——`parseTurnLevel`（`AgentBeans.java:114-124`）catch `IllegalArgumentException` 后回退 SNIP。

装配日志（`AgentBeans.java:101-104`）会把全部七个值打出来，启动时可以直接核对。

---

## 4. `eon.budget` / `eon.loop` — 预算与步数

| 键 | 默认 | yml | 消费方 |
|---|---|---|---|
| `budget.max_tokens` | `2000000` | 同 | `BudgetHook:45`，`used >= max` → `stop(BUDGET_EXCEEDED)` |
| `budget.threshold` | `0.75` | 同 | `BudgetHook:55`，`ratio >= threshold` → 注入收尾 nudge |
| `loop.max_steps` | `100` | 同 | `AgentEngine.java:90` 循环开头检查；`BudgetHook:56` 算剩余轮数 |

`budget.max_tokens` 读的是 **`session().usageAccum()` 的会话累计值**（跨任务累加、从快照恢复），不是单次任务的消耗。所以一个长期使用的会话最终会撞上预算上限，即使每个任务都很小。

两者的关系：`max_steps` 是循环开头的静态闸门（硬失败），`budget.threshold` 是钩子链里的软着陆（先提示模型收尾，耗尽才硬停）。

---

## 5. `eon.loop_detect` — 守卫阈值

`AgentConfig.LoopDetectConfig`（`:446-452`）。**同一组配置同时喂给三个不同的守卫**，且分别在不同作用域构造：

| 键 | 默认 | yml | 消费者 | 作用域 |
|---|---|---|---|---|
| `repeat_warn` | `3` | 同 | `LoopDetector` ← `TaskScope:29` | task |
| `repeat_stop` | `5` | 同 | `LoopDetector` ← `TaskScope:29` | task |
| `no_progress_steps` | `6` | 同 | `ProgressTracker` ← `TaskScope:30`（作为**滑动窗口大小**） | task |
| `failure_warn` | `3` | yml 有 | `ToolCircuitBreaker` ← `SessionScopeLoader:132-133` | **session** |
| `failure_stop` | `5` | yml 有 | 同上 | **session** |
| `cooldown_turns` | `3` | **yml 缺失** | 同上 | **session** |

`no_progress_steps=6` 的语义容易被误读：它是 `ProgressTracker` 的**窗口大小**，而告警条件是"连续 ≥2 个完整窗口无变化"（`TodoNoProgressHook:33`），所以实际至少需要 **12 次内容完全相同的 `todo_write`** 才告警。nudge 文案里报的数字是 `windowSize * stepsWithoutProgress`（`:35`）。

---

## 6. `eon.retry` — LLM 重试

`AgentConfig.RetryConfig`（`:503-507`），仅作用于**同步路径** `LlmClient.chat`（`:82-125`）。

| 键 | 默认 | yml | 含义 |
|---|---|---|---|
| `attempts` | `3` | 同 | 最大尝试次数（含首次） |
| `min_delay_ms` | `500` | 同 | 退避起始延迟 |
| `max_delay_ms` | `5000` | 同 | 退避上限 |
| `jitter` | `0.2` | 同 | 抖动系数（±20%） |

延迟公式（`LlmClient.java:234-238`）：

```java
long base   = (long) (minDelayMs * Math.pow(2, attempt - 1));
long jitter = (long) (base * jitterCoeff * (Math.random() - 0.5) * 2);
return Math.min(maxDelayMs, base + jitter);
```

**流式路径完全不重试**（`streamChat` 里没有重试循环），失败直接抛 `LlmStalledException`。`stream_enabled=true` 是默认值，所以生产上实际走的是无重试路径。

---

## 7. `eon.storage` / `eon.session.cache` — 存储与缓存

| 键 | 默认 | yml | 含义 |
|---|---|---|---|
| `storage.base_dir` | `/home/workspace/sessions` | `./home/workspace/sessions` | 会话数据根目录。**相对路径基于进程工作目录** |
| `storage.db_path` | `/home/workspace/sessions/eon.db` | `./home/workspace/sessions/eon.db` | SQLite 文件路径 |
| `session.cache.maximum_size` | `1000` | 同 | 缓存条目上限 |
| `session.cache.idle_ttl_minutes` | `30` | 同 | IDLE 状态 TTL |
| `session.cache.running_ttl_minutes` | `1440` | 同 | RUNNING 状态 TTL，「兜底防长任务被淘汰」 |
| `session.cache.busy_policy` | `"REJECT"` | 同 | 同会话并发策略：`REJECT`(409) / `QUEUE`(排队) |
| `session.cache.queue_timeout_seconds` | `60` | 同 | QUEUE 模式最长等待；**≤0 时回退 60**（`SessionRegistry:124-128`） |

`base_dir` 被三处独立读取并各自 `Path.of(...).toAbsolutePath().normalize()`：`SessionScopeLoader:63`、`SessionIndexStore:30`、`MemoryStore:30`、`FileService:122`。四处必须一致，否则文件接口读不到工具写的文件。

`running_ttl_minutes` 的实际作用比看起来弱：`SessionExpiry` 在 **read 时也重算 TTL**（`SessionExpiry:29-31`），所以只要任务在跑（不断读缓存/不断 emit），TTL 就会被持续续期。1440 分钟是"完全无活动"情况下的兜底。

`busy_policy` 的判断是 `"QUEUE".equalsIgnoreCase(...)`（`SessionRegistry:120-122`），所以除 `QUEUE`（不分大小写）外的**任何值都等同 REJECT**，包括拼错的值——不会报错。

---

## 8. `eon.tools` — 工具

`AgentConfig.ToolsConfig`（`:630-670`）。

| 键 | 默认 | yml | 含义与生效位置 |
|---|---|---|---|
| `whitelist` | 空 `LinkedHashSet` | 9 项 | 本地工具注册过滤（`ToolService.java:32-35`）。**空集合表示不过滤**（`!whitelist.isEmpty()` 短路）。MCP 工具不受限 |
| `sandbox_enabled` | `true` | 同 | 路径沙箱（`PathResolver:49`），同时作用于工具与 HTTP 文件接口（`FileService:132`） |
| `parallelism` | `4` | 同 | `tool-exec` 固定线程池大小（`ToolCallDispatcher:49-50`，`Math.max(1, ...)` 保底） |
| `auto_approve_destructive` | `true` | **缺失** | GateHook 行为（`GateHook:26`、`:30`，构造期读一次） |
| `web_fetch.max_content_length` | `50000` | 同 | 网页正文截断字符数 |
| `web_fetch.cache_ttl_minutes` | `15` | 同 | 抓取结果 LRU 缓存 TTL |
| `web_fetch.cache_max_entries` | `64` | 同 | LRU 上限 |
| `download.max_file_size_mb` | `100` | 同 | 下载上限，装配时 ×1024×1024 转字节（`AgentBeans:53`） |

`webFetch` 与 `download` 两个嵌套对象在配置类里**默认为 `null`**（`:636-637`，没有 `new`）。`AgentBeans:72-81` 因此对 `webFetch` 做了 null 判断并回退到无参 `WebFetchTool.descriptor()`；`download` 没有做 null 判断（`:52-54`），yml 里删掉这一组会 NPE。

`whitelist` 用 `Set<String>` 但声明为 `LinkedHashSet`，注册日志按插入序输出。

---

## 9. `eon.mcp` — MCP 服务

`AgentConfig.McpConfig`（`:730-750`）+ `McpServerConfig`（`:752-756`）。

```yaml
eon:
  mcp:
    servers:
      reader-mcp-server:              # ← map key，即 serverKey
        url: "http://124.223.110.114:8081/mcp"
        enabled: true
        permission: "READONLY"        # ← 当前不生效，见下
```

| 字段 | 默认 | 含义 |
|---|---|---|
| `key` | 无 | **由 `setServers` 从 map key 回填**（`:737-745`，注释：「Spring Boot 绑定时不把 map key 注入 value 的 key 字段」） |
| `url` | `""` | Streamable HTTP 端点。空白则记 WARN 跳过（`AgentBeans:174-177`） |
| `enabled` | `true` | `getEnabledServers()` 过滤（`:747-749`） |
| `permission` | `"READONLY"` | **仅出现在注册日志里**，`ToolService.getPermission()` 对 MCP 工具硬编码返回 READONLY（`ToolService:132-134`）。见 [08-design-gaps.md](08-design-gaps.md) 第 2 条 |

`permission` 的合法值由 `ToolService.parsePermission`（`:64-72`）决定：`READONLY` / `RESTRICTED_WRITE`（也接受 `RESTRICTEDWRITE`）/ `DESTRUCTIVE`，其他一律回退 READONLY——**不会因拼错而启动失败**。

连接失败不阻断启动：`McpServerClient.connect()` 抛 `RuntimeException`（`:47-50`），被 `AgentBeans.connectMcpServers:186-188` catch 后记 ERROR 跳过。**无重连机制**，见 [04-tools.md](04-tools.md) 第 8 节。

---

## 10. `eon.web_search` — 搜索工具参数

`AgentConfig.WebSearchConfig`（`:792-799`）。yml:28 的注释说明了这组的性质：

> 以下几项是工具参数的缺省值：LLM 调用显式传参时以参数为准，未传时回退到这里

| 键 | 默认 | yml | 含义 |
|---|---|---|---|
| `api_key` | `""` | `${QIANFAN_API_KEY}` | **为空则 `web_search` 整个工具不注册**（`AgentBeans:58-70`，记 WARN「web_search 工具未注册: QIANFAN_API_KEY 未配置」） |
| `search_source` | `baidu_search_v2` | 同 | 直传百度千帆 API |
| `top_k` | `10` | 同 | 工具未传 `max_results` 时的默认返回条数 |
| `recency_filter` | `py` | 同 | 时效过滤：`pd`(天)/`pw`(周)/`pm`(月)/`py`(年) |

---

## 11. `eon.mode`

| 键 | 默认 | yml | 含义 |
|---|---|---|---|
| `snapshot_enabled` | `true` | 同 | 三处消费：① `SessionSnapshotHook.active()`（`engine/hook/posttool/SessionSnapshotHook.java:34-36`）② `SessionScope.saveSnapshot()` 空操作判断（`SessionScope:120-123`）③ 装配时存为 final 字段（`SessionScopeLoader:144`） |

关掉它意味着：`todo_write` 不再触发快照，任务结束与淘汰时也不写 `state.json`。后果是所有会话恢复都退化为 `RestoreMode.LOAD`（全量回放），且 todos 与 token 累计在重启后丢失。

---

## 12. `eon.interaction` — 人机交互（yml 中完全缺失）

`AgentConfig.InteractionConfig`（`:399-405`）。

| 键 | 默认 | 消费方 | 含义 |
|---|---|---|---|
| `timeout_seconds` | `600` | `InteractionGateway:30`（构造期读一次存 final） | `ask_question` 阻塞等答案的上限 |
| `sse_timeout_seconds` | `1800` | `ChatServiceImpl:89` | `SseEmitter` 超时 |

**这两个值有硬性的数量关系**，yml:88 的注释说明了原因：

> 一次 run 里可能多次阻塞等用户回答，超时必须覆盖「执行 + 等待」，不能沿用默认的 5 分钟

即 `sse_timeout_seconds` 必须 > `任务执行时长 + N × timeout_seconds`。当前 1800 > 600，只够覆盖两次完整提问等待。如果模型连续问三次且用户都拖到超时，SSE 会先断开。调整 `timeout_seconds` 时必须同步评估 `sse_timeout_seconds`。

---

## 13. 环境变量

| 变量 | 必需 | 缺失后果 |
|---|---|---|
| `LLM_API_KEY` | **是** | 所有 LLM 调用（含摘要）失败 → `LlmStalledException` → `UNEXPECTED_ERROR` |
| `QIANFAN_API_KEY` | 否 | `web_search` 静默不注册，模型看不到这个工具（启动日志有 WARN） |

两者都在 yml 里以 `${VAR}` 形式引用，**没有 `:default` 兜底**，所以变量未设置时 Spring 会解析为空字符串而非启动失败。

---

## 14. `system_prompt.md` 结构

`src/main/resources/prompts/system_prompt.md`，67 行，启动时一次性读入为 String bean（`config/AgentBeans.java:127-144`，注入处需 `@Qualifier("systemPrompt")`）。加载失败会记 ERROR 并**返回空字符串**（`:142-143`）而不是启动失败——所以提示词丢失的表现是模型行为异常，不是启动报错。

| 段落 | 行号 | 职责 | 与上下文锚点的对应 |
|---|---|---|---|
| 开场 | 1-9 | 身份（Eon / 孔明灯）、智能体定位、「尽你所能不等于硬撑到底」 | `:9` 声明用户指示由 `<user_input>` 标签表示 ← 对应 `MessageBlockCodec.assemble` 给用户消息包的标签 |
| `<communication>` | 11-23 | 中文回答、反引号标注术语、LaTeX 分隔符；**每轮结尾必须给 2~3 条「接下来你可以问」** | — |
| `<tool_calling>` | 25-32 | 先理解意图再选工具、只调当前提供的工具、**永不向用户提及工具名**、不确定就先读 | 与 `ToolValidationHook` 的 nudge 互补 |
| `<honesty>` | 34-40 | 诚实优先于完成；工具报错要如实告知发生在哪一步 | — |
| `<asking_user>` | 42-46 | 何时必须问 / 何时不必问 | 直接约束 `ask_question` 的使用 |
| `<task_management>` | 48-51 | `todo_write` 是手段不是流程，简单请求直接做 | 与 `TodoContextHook` / `TodoNoProgressHook` 互补 |
| `<maximize_context_understanding>` | 53-57 | 检索要彻底、多轮换措辞、能自查就自查 | — |
| `<content_generation>` | 59-62 | 输出可立即使用；**同一主题循环修正不超过 3 次** | 与 `LoopDetectHook` 的 warn=3 阈值呼应 |
| `<memories>` | 64-67 | 记忆可能过时；**必须以 `[[memory:MEMORY_ID]]` 格式引用** | 对应 `MemoryStore.renderReferences` 的正则 `\[\[memory:(mem_[a-f0-9]+)\]\]` |

其余五个锚点标签（`<memories>` `<summary>` `<environment>` `<todo>` `<nudges>`）在提示词里**没有显式说明**，只有 `<user_input>` 在 `:9` 被提及。它们的语义靠 `ContextBuilder` 的 XML 包裹约定与模型自身的理解。

---

## 15. 装配入口速查

`config/AgentBeans.java` 是"组合根"，四个 `@Bean` + 一个 `@PreDestroy`：

| Bean | 行号 | 产出 |
|---|---|---|
| `toolService(config, objectMapper, httpConfig)` | `:43-89` | `ToolService`，`destroyMethod="closeAll"`。硬编码注册 9 个内置工具（含条件注册 `web_search`）+ MCP 远程工具 |
| `compressionPolicy(config, compressor, llmService)` | `:92-111` | 构造 `ContextSummarizer` + `CompressionSettings` → `CompressionPolicy` |
| `systemPrompt(config, resourceLoader)` | `:127-144` | `@Bean("systemPrompt") String`，从 classpath 一次性读取 |
| `tokenCountEstimator()` | `:147-150` | `new OpenAiTokenCountEstimator("gpt-4o")` — **写死 gpt-4o**，与 `eon.llm.model_name` 无关 |
| `shutdown()` | `:152-162` | `@PreDestroy`，逐个关闭 MCP 客户端 |

其他 `@Configuration`：

| 类 | 产出 |
|---|---|
| `config/ExecutorConfig.java:18-25` | `@Bean(name="sseExecutor", destroyMethod="shutdown")` — `newCachedThreadPool`，daemon，线程名 `sse-push` |
| `runtime/cache/SessionCacheConfig.java:18-34` | `Cache<String, SessionScope>` — maximumSize + 变长过期 + removalListener + `executor(Runnable::run)` 同步回调 + recordStats |
| `store/db/SqliteConfig.java:36-89` | `DataSource`（`org.sqlite.SQLiteDataSource`）+ PRAGMA + DDL |
| `config/HttpConfig.java` | 工具共用的 `HttpClient`（`download_file` / `web_search` / `web_fetch`） |
| `config/ObjectMapperConfig.java` | 全局 `ObjectMapper` |

---

## 相关篇章

- [01-engine.md](01-engine.md) — `loop.max_steps` / `llm.max_tokens` / `context.max_tokens` 在循环里的消费点
- [02-context.md](02-context.md) — `context.*` 与 `compression.*` 的完整语义
- [03-hooks.md](03-hooks.md) — `budget.*` / `loop_detect.*` / `mode.snapshot_enabled` / `tools.auto_approve_destructive` 的守卫阈值
- [04-tools.md](04-tools.md) — `tools.*` / `mcp.*` / `web_search.*` / `interaction.*`
- [05-events-api.md](05-events-api.md) — `llm.*` / `retry.*` 与 LLM 层的重试、超时行为
- [06-persistence.md](06-persistence.md) — `storage.*` / `session.cache.*`
- [08-design-gaps.md](08-design-gaps.md) — `auto_approve_destructive` 与 MCP `permission` 的配置失效问题
