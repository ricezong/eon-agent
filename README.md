# Eon Agent

基于 Java 17 + LangChain4j 构建的 LLM Agent 框架。支持工具调用、上下文压缩、优雅停止、死循环检测、MCP 协议扩展，适用于复杂多步骤任务场景。

## 目录

- [架构总览](#架构总览)
- [核心循环](#核心循环)
- [Hook 体系](#hook-体系)
- [上下文管理](#上下文管理)
- [工具系统](#工具系统)
- [熔断与循环检测](#熔断与循环检测)
- [优雅停止机制](#优雅停止机制)
- [存储层](#存储层)
- [MCP 集成](#mcp-集成)
- [配置说明](#配置说明)
- [快速开始](#快速开始)

---

## 架构总览

```
                    ┌─────────────────────────────────────────────┐
                    │                  EonAgent                    │
                    │                                             │
   用户输入 ──────► │  ┌───────────┐  ┌───────────┐  ┌─────────┐ │ ──► 最终输出
                    │  │ Core Loop  │  │ Extension │  │  Stop   │ │
                    │  │ (LLM调用) │─►│ Loop(工具) │─►│ Handler │ │
                    │  └─────┬─────┘  └─────┬─────┘  └─────────┘ │
                    │        │              │                     │
                    │  ┌─────▼──────────────▼─────┐               │
                    │  │     HookDispatcher       │               │
                    │  │  PreModel/PostModel/     │               │
                    │  │  PreTool/PostTool        │               │
                    │  └──────────────────────────┘               │
                    └─────────────────────────────────────────────┘
                                        │
                    ┌───────────────────┼───────────────────┐
                    │                   │                   │
              ┌─────▼─────┐      ┌──────▼──────┐     ┌──────▼──────┐
              │  LlmClient │      │ToolRegistry │     │  Stores     │
              │ (重试+退避) │      │(本地+MCP)   │     │(JSONL/Art/  │
              └───────────┘      └─────────────┘     │ CP/Todo/Ins)│
                                                    └─────────────┘
```

### 包结构

```
cn.kong.eon
├── agent/          # Agent 引擎核心
│   ├── EonAgent.java           # 主引擎：Core Loop + Extension Loop + Stop Handler
│   ├── TurnAction.java         # Turn 返回值（Continue/Exit）
│   ├── hook/                   # Hook 接口体系
│   │   ├── Hook.java           # 四阶段接口：PreModel/PostModel/PreTool/PostTool
│   │   ├── HookResult.java     # 返回值：ok() / stop(reason)
│   │   ├── StopReason.java     # 停止原因：category + message + graceSteps
│   │   ├── StopCategory.java   # 停止类别枚举
│   │   ├── premodel/           # BudgetHook, ContextCompactHook, TodoNavigatorHook
│   │   ├── postmodel/          # LoopDetectHook
│   │   ├── pretool/            # GateHook
│   │   └── posttool/           # FailureBreakerHook, CheckpointHook
│   └── support/                # 辅助组件
│       ├── HookDispatcher.java # Hook 调度器（统一遍历+stop处理）
│       ├── ToolExecutionHandler.java  # 工具执行处理器
│       ├── TurnLogger.java     # Turn 日志收集器
│       └── TurnRecord.java     # 单轮日志结构
├── bootstrap/       # 启动器
├── config/          # 配置加载（agent.yaml → AgentConfig）
├── context/         # 上下文构建与压缩
│   ├── ContextBuilder.java     # 分层组装 messages
│   ├── CompressionEngine.java  # 三级递进压缩
│   └── PairingRepairer.java    # tool_use/tool_result 配对修复
├── llm/             # LLM 客户端
├── loop/            # 循环检测器
├── mcp/             # MCP 客户端管理
├── model/           # 数据模型（SessionState, TodoItem, Checkpoint 等）
├── store/           # 存储层（JSONL, Artifact, Checkpoint, Todo, Insights）
├── tool/            # 工具框架
│   ├── ToolRegistry.java       # 工具注册表（本地+MCP 统一管理）
│   ├── ToolDescriptor.java     # 工具描述符（名称/Schema/权限/执行器）
│   ├── ToolExecutor.java       # 执行器接口（@FunctionalInterface）
│   ├── ToolOutcome.java        # 执行结果（success/failure + content）
│   ├── ToolResultRenderer.java # 结果渲染器（大文本落盘 artifact）
│   ├── ArgumentSanitizer.java  # 参数类型清洗器
│   ├── ToolContext.java        # 工具运行时上下文
│   └── builtin/               # 9 个内置工具
└── util/            # 工具类（JsonMapper, FileUtils）
```

---

## 核心循环

Agent 采用 **Core Loop + Extension Loop** 双层循环设计：

### Core Loop（模型调用层）

```
while (shouldContinue) {
    Turn N:
    1. 组装上下文 (ContextBuilder)
    2. PreModel Hooks → BudgetHook → TodoNavigatorHook → ContextCompactHook
    3. 调用 LLM
    4. 无工具调用？→ 退出或处理截断
    5. PostModel Hooks → LoopDetectHook（循环检测）
    6. 进入 Extension Loop
    7. 回填消息到 JSONL
    8. finish 检测 / grace step 消耗
}
```

### Extension Loop（工具执行层）

```
1. PreTool Hooks → GateHook（破坏性工具门禁）
2. 逐个执行工具调用:
   - 检查工具是否被熔断 → 被熔断则跳过，返回合成错误
   - 参数解析 → ArgumentSanitizer 类型清洗 → 执行 → 结果渲染
   - finish 拦截：finish 成功后跳过后续工具
   - todo_write 后处理：激活 TodoNavigator + 记录快照
3. PostTool Hooks → FailureBreakerHook → CheckpointHook
```

### Turn 执行流程

`EonAgent.executeTurn()` 是单个 Turn 的完整执行：

1. 创建 `TurnRecord`（日志收集器）
2. `buildContext()` → 组装 `ContextBuilder`（系统提示词、摘要、transcript、工具目录、导航、nudge）
3. `firePreModelHooks()` → 预算检查、Todo 导航渲染、上下文压缩
4. 构建 messages + 获取工具 Schema → 调用 `LlmClient.chat()`
5. 无工具调用 → `handleNoToolCalls()`（截断处理 / grace 消耗 / 正常退出）
6. `firePostModelHooks()` → 循环检测
7. `executeExtensionLoop()` → PreTool → Execute → PostTool
8. `finalizeAndAppend()` → AI 消息和工具结果回填 JSONL
9. finish 检测 / stop grace step 消耗

### 退出条件

| 场景 | 触发方式 | 行为 |
|------|---------|------|
| LLM 无工具调用 | 直接返回文本 | 正常退出，输出文本 |
| finish 工具 | LLM 调用 `finish` | 设置 `finished=true`，summary 作为输出 |
| maxSteps | 达到 `loop.max_steps` | 触发优雅停止 |
| 预算超限 | Token 累计达到硬阈值 | 触发优雅停止 |
| 死循环 | 同参数重复调用 5 次 | 触发优雅停止 |
| LLM 不可用 | 重试 3 次全失败 | 立即硬终止（`LlmStalledException`） |

---

## Hook 体系

Hook 是 Agent 引擎的扩展机制。每个 Hook 只属于一个执行阶段，通过 `order()` 控制阶段内执行顺序。

### 四阶段

| 阶段 | 时机 | 接口 | 调度策略 |
|------|------|------|---------|
| PreModel | LLM 调用前 | `Hook.PreModelHook` | stop 后继续遍历后续 hook |
| PostModel | LLM 响应后 | `Hook.PostModelHook` | stop 后 finalize + skip |
| PreTool | 工具执行前 | `Hook.PreToolHook` | stop 后 finalize + skip |
| PostTool | 工具执行后 | `Hook.PostToolHook` | stop 后 finalize + skip |

### Hook 列表

| Hook | 阶段 | Order | 功能 |
|------|------|-------|------|
| `BudgetHook` | PreModel | 10 | Token 预算检查：软阈值注入 nudge，硬阈值请求停止 |
| `TodoNavigatorHook` | PreModel | 20 | Todo 列表 + Insights 渲染到上下文（todo_write 调用后激活） |
| `ContextCompactHook` | PreModel | 100 | 三级递进上下文压缩（必须最后执行） |
| `LoopDetectHook` | PostModel | 30 | 重复调用检测 + 熔断工具拦截 |
| `GateHook` | PreTool | 20 | 破坏性工具门禁：校验必要参数 |
| `FailureBreakerHook` | PostTool | 30 | 单工具连续失败检测与熔断 |
| `CheckpointHook` | PostTool | 100 | todo_write 成功后保存 Checkpoint 快照 |

### Hook 调度（HookDispatcher）

- **PreModel** 特殊：stop 后继续遍历（BudgetHook stop 后 ContextCompactHook 仍需执行）
- **其他阶段**：stop 后 finalize pending 消息并 skip（跳过后续 hook）
- 返回 `FireResult`：`Continue` / `Skip` / `Exit`，无 null 歧义

### HookResult

- `ok()` — 继续执行
- `stop(StopReason)` — 请求优雅停止，由 `EonAgent.handleStop()` 统一处理

---

## 上下文管理

### ContextBuilder 分层组装

消息物理顺序（保证 KV Cache 前缀稳定）：

```
1. SystemMessage    — 系统提示词（不变）
2. SystemMessage    — 历史对话摘要（压缩后生成）
3. Transcript       — 对话历史（来自 JSONL Store）
4. UserMessage      — 可用工具目录
5. UserMessage      — Todo 导航 + Insights
6. UserMessage      — 运行时提醒（nudge，本轮有效）
7. TailGuard        — 尾部保护消息（最近 N 轮不压缩）
```

### 三级递进压缩（CompressionEngine）

根据上下文水位（usedTokens / maxTokens）自动触发：

| 水位 | 策略 | 说明 |
|------|------|------|
| ≥ 65% (`snip_threshold`) | **Snip** | 截短旧 tool result，保留前 80 字符 + 引用标记 |
| ≥ 82% (`prune_threshold`) | **Prune** | 替换为占位符（隐含 Snip），保留 30 字符 |
| ≥ 95% (`summarize_threshold`) | **Summarize** | LLM 生成摘要，删除旧消息 |

**Tail Guard**：最近 3 轮（6 条消息 + 2 条缓冲）不参与压缩。

**Summarize 后**：调用 `PairingRepairer` 修复因删消息导致的 `tool_use/tool_result` 配对断裂：
- 匹配的 tool_result 移到 tool_use 之后
- 丢弃孤立的 tool_result
- 为缺失结果的 tool_use 插入合成错误消息
- 按 tool_use_id 去重

### 大文本落盘（ToolResultRenderer）

工具结果 > 3000 字符时，原文落盘为 `Artifact`，上下文只保留摘要（前 700 + 后 300 字符）+ 引用 `artifact://art_001`。

---

## 工具系统

### 工具注册表（ToolRegistry）

统一管理本地工具和 MCP 远程工具：

- **本地工具**：通过 `ToolDescriptor` 注册，受 `whitelist` 过滤
- **MCP 工具**：通过 `McpClientManager` 注册，不受白名单限制
- 执行时自动路由：先查本地 → 再查 MCP

### 工具权限分级

| 权限 | 说明 | 工具 |
|------|------|------|
| `READONLY` | 只读，无副作用 | `web_search`, `web_read`, `date_time`, `todo_read` |
| `RESTRICTED_WRITE` | 有限写入（工作目录/状态） | `file_io`, `todo_write`, `working_memory`, `finish` |
| `DESTRUCTIVE` | 破坏性操作（写文件系统） | `download` |

### 内置工具（9 个）

| 工具 | 描述 | 权限 |
|------|------|------|
| `todo_write` | 创建/更新任务清单（全量替换语义），校验单一焦点与依赖完整性 | RESTRICTED_WRITE |
| `todo_read` | 读取当前任务清单与进度统计 | READONLY |
| `working_memory` | 写入关键发现到 Insights 滚动区（上限 40 条） | RESTRICTED_WRITE |
| `finish` | 总结任务并终止循环。Todo 未完成时阻止 `goal_achieved=true` | RESTRICTED_WRITE |
| `web_search` | 百度千帆 AI Search 搜索网页 | READONLY |
| `web_read` | Jsoup 抓取网页，提取正文文本 | READONLY |
| `download` | 通用文件下载（破坏性操作） | DESTRUCTIVE |
| `file_io` | 文件读写（read/write/list/delete），路径禁止 `..` 穿越 | RESTRICTED_WRITE |
| `date_time` | 获取当前系统日期和时间 | READONLY |

### 工具执行流程

```
ToolExecutionHandler.execute():
  for each ToolExecutionRequest:
    1. 检查 isToolTripped → 被熔断则跳过，返回合成错误
    2. parseArgs → JSON 解析参数
    3. toolRegistry.execute():
       a. ArgumentSanitizer.sanitize() → 类型清洗（LLM 可能传错类型）
       b. ToolExecutor.execute() → 实际执行
       c. 或 McpClientManager.executeTool() → MCP 远程执行
    4. ToolResultRenderer.render() → 大文本落盘 artifact
    5. 记录日志
    6. finish 拦截：设置 finished 后跳过后续工具
    7. todo_write 后处理：激活 TodoNavigator + 记录快照
```

### 参数类型清洗（ArgumentSanitizer）

LLM 不一定按 Schema 传参，`ArgumentSanitizer` 根据 ToolSpecification 声明的类型自动转换：
- String → List（JSON 数组字符串解析为 List）
- String → Boolean（`"true"` / `"false"` 转布尔）
- String → Integer / Double（数字字符串转数值）

### 工具 Schema 构建

`ToolDescriptor.buildSpec()` 自动为所有工具注入 `reason` 必填字段，要求 LLM 在每次调用时说明动机。

---

## 熔断与循环检测

`LoopDetector` 提供三种检测，共享状态由 `LoopDetectHook`（PostModel）和 `FailureBreakerHook`（PostTool）共用：

### 1. 重复调用检测

同一工具 + 同一参数连续调用超过阈值：

| 阈值 | 行为 |
|------|------|
| `repeat_warn` (3) | 注入 WARN nudge：提示换参数或换工具 |
| `repeat_stop` (5) | 返回 STOP：请求优雅停止 |

成功调用后重置该工具的指纹计数。

### 2. 单工具熔断

单个工具连续失败超过阈值（**不影响其他工具**）：

| 阶段 | 行为 |
|------|------|
| `failure_warn` (3) | 注入 WARN nudge：提示标记 blocked 或调整计划 |
| `failure_stop` (5) | 工具加入 `trippedTools`，返回 STOP |

**熔断后的行为**：
- `LoopDetectHook`（PostModel）：检测到熔断工具返回 WARN（不阻止其他工具执行）
- `ToolExecutionHandler`（Extension Loop）：被熔断的工具跳过执行，返回合成错误
- `FailureBreakerHook`（PostTool）：熔断 STOP 降级为 nudge（不触发会话级停止）
- **其他工具正常执行，会话不终止**

成功调用后重置该工具的失败计数和熔断状态。

### 3. 无进展检测

`todo_write` 后记录 Todo 快照，连续 `no_progress_steps` (6) 步 Todo 状态无变化时注入 WARN nudge。

---

## 优雅停止机制

所有终止场景统一走 `StopReason` → `EonAgent.handleStop()`：

### 停止类别（StopCategory）

| 类别 | 触发条件 | 来源 |
|------|---------|------|
| `BUDGET_EXCEEDED` | Token 累计达到硬阈值 | BudgetHook |
| `LOOP_DETECTED` | 同参数重复调用 5 次 | LoopDetectHook |
| `GATE_REJECTED` | 破坏性工具缺少必要参数 | GateHook |
| `FAILURE_BREAKER` | 单工具连续失败 5 次（降级为 nudge，不触发会话停止） | FailureBreakerHook |
| `MAX_STEPS_REACHED` | 达到最大步数 | EonAgent |
| `UNEXPECTED_ERROR` | 未预期异常 | EonAgent |

### Grace Period 流程

```
StopReason(graceSteps=N) 
  → handleStop():
     1. state.stopState.request(reason) → 设置 remainingGraceSteps=N
     2. 注入收尾 nudge（引导 LLM 调用 finish）
     3. finalize pending 消息
     4. 返回 Continue（继续循环）

后续每轮：
  - LLM 调用 finish → 正常退出
  - LLM 调用非 finish 工具 → consumeGraceStep()，remaining--
  - LLM 无工具调用 → consumeGraceStep()，remaining--
  - remaining=0 → forceTerminate()（硬终止，输出终止原因 + 消耗统计）

graceSteps=0 → 立即硬终止
```

### 硬终止输出

```
任务终止: 检测到死循环
原因: 重复调用同一工具同一参数 5 次，疑似死循环
消耗: 50000 tokens, 15 轮
```

---

## 存储层

按 `sessionId` 隔离，存储在 `{storage.base_dir}/{sessionId}/` 下：

| 存储 | 文件 | 说明 |
|------|------|------|
| `JsonlStore` | `transcript.jsonl` | Append-Only 消息账本，永不修改已有消息 |
| `ArtifactStore` | `artifacts/art_001_*.txt` | 大文本工具结果落盘，上下文只保留引用 |
| `CheckpointStore` | `checkpoints/cp_001.json` | 崩溃恢复快照（Todo + Token + 压缩状态 + Insights） |
| `TodoStore` | 内存 | Todo 列表，随 Checkpoint 落盘 |
| `InsightsStore` | 内存 | 关键发现滚动区（上限 40 条/8000 字符） |

### Checkpoint 恢复

启动时如果检测到最新 Checkpoint，自动恢复 Todo 列表。Checkpoint 在每次 `todo_write` 成功后保存。

---

## MCP 集成

通过 LangChain4j MCP 客户端连接外部 MCP 服务，自动注册远程工具：

```yaml
mcp:
  servers:
    novel-mcp-server:
      url: "http://example.com/mcp"
      enabled: true
      permission: "READONLY"
```

- 支持多 MCP 服务，每个服务的工具统一注册到 `ToolRegistry`
- MCP 工具不受本地白名单限制
- MCP 工具默认权限为 `READONLY`
- JVM 关闭时自动断开 MCP 连接

---

## 配置说明

配置文件：`src/main/resources/config/agent.yaml`

### LLM 配置

```yaml
llm:
  provider: "mimo"           # 提供商标识
  base_url: "https://..."    # API 地址（OpenAI 兼容）
  api_key: "${LLM_API_KEY}"  # 环境变量引用
  model_name: "mimo-v2.5"
  temperature: 0.7
  timeout: 120               # 请求超时（秒）
  max_tokens: 12800          # 单次最大输出 token
```

### 上下文配置

```yaml
context:
  max_tokens: 120000         # 上下文窗口大小
  budget_ratio: 0.7          # 输入 token 占比上限
  compression:
    snip_threshold: 0.65     # 截短水位
    prune_threshold: 0.82    # 占位符水位
    summarize_threshold: 0.95 # 摘要水位
```

### 预算配置

```yaml
budget:
  max_tokens: 10000000       # 会话累计 token 上限
  soft_threshold: 0.75       # 软阈值：注入收尾 nudge
  hard_threshold: 1.0        # 硬阈值：触发优雅停止
  grace_steps: 3             # 触发后额外轮次
```

### 循环检测配置

```yaml
loop_detect:
  repeat_warn: 3             # 同工具同参数重复 3 次告警
  repeat_stop: 5             # 重复 5 次判定死循环
  no_progress_steps: 6       # Todo 连续 6 步无变化告警
  failure_warn: 3            # 单工具连续失败 3 次注入警告
  failure_stop: 5            # 单工具连续失败 5 次熔断该工具
```

### 工具配置

```yaml
tools:
  whitelist:                 # 允许注册的本地工具
    - "todo_write"
    - "todo_read"
    - "working_memory"
    - "finish"
    - "web_search"
    - "web_read"
    - "download"
    - "file_io"
    - "date_time"
  destructive:               # 破坏性工具
    - "download"
  readonly:                  # 只读工具
    - "todo_read"
    - "web_search"
    - "web_read"
    - "date_time"
```

### 环境变量

| 变量 | 用途 |
|------|------|
| `LLM_API_KEY` | LLM API Key |
| `QIANFAN_API_KEY` | 百度千帆 AI Search API Key |

---

## 快速开始

### 前置条件

- JDK 17+
- Maven 3.8+
- LLM API Key（OpenAI 兼容接口）
- 百度千帆 API Key（web_search 工具，可选）

### 编译

```bash
mvn clean compile
```

### 运行

```bash
# 设置环境变量
export LLM_API_KEY=your_api_key
export QIANFAN_API_KEY=your_qianfan_key

# 启动
mvn exec:java
```

### 测试

```bash
mvn test
```

### 交互

启动后输入问题或任务，Agent 自动决定调用哪些工具并执行：

```
========================================
  Eon Agent v2.0
  LLM: mimo / mimo-v2.5
  MCP servers: [novel-mcp-server]
========================================

请输入问题或任务: 帮我搜索今天的天气
```

---

## 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 运行时 |
| LangChain4j | 1.18.0 | LLM 框架 |
| LangChain4j MCP | 1.18.1-beta28 | MCP 协议客户端 |
| Jackson | 2.18.2 | JSON 序列化 |
| SnakeYAML | 2.3 | YAML 配置解析 |
| Jsoup | 1.18.3 | HTML 解析（web_read） |
| SLF4J + Logback | 2.0.16 / 1.5.12 | 日志 |
| JUnit 5 | 5.11.4 | 测试框架 |
| AssertJ | 3.26.3 | 断言库 |
