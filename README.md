# Eon Agent

> 基于 Java 17 + LangChain4j + Spring Boot 3 的自主智能体（Agent）框架。
> 支持 CLI 与 HTTP API 双模式，具备完整的 Agent Core Loop、Hook 扩展体系、上下文压缩、死循环检测、优雅停止、MCP 协议集成等核心能力。

---

## 目录

- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [架构总览](#架构总览)
- [核心流程](#核心流程)
  - [1. Agent Core Loop（主循环）](#1-agent-core-loop主循环)
  - [2. 上下文构建与分层注入](#2-上下文构建与分层注入)
  - [3. Hook 扩展体系](#3-hook-扩展体系)
  - [4. 上下文压缩（三级递进）](#4-上下文压缩三级递进)
  - [5. 工具系统](#5-工具系统)
  - [6. 优雅停止与 Grace Period](#6-优雅停止与-grace-period)
  - [7. 死循环检测与熔断](#7-死循环检测与熔断)
  - [8. 会话管理与生命周期](#8-会话管理与生命周期)
  - [9. HTTP API 与 SSE 流式推送](#9-http-api-与-sse-流式推送)
  - [10. 异步交互（AskQuestion）](#10-异步交互askquestion)
  - [11. 持久化与崩溃恢复](#11-持久化与崩溃恢复)
  - [12. MCP 协议集成](#12-mcp-协议集成)
- [配置说明](#配置说明)
- [快速启动](#快速启动)
- [内置工具一览](#内置工具一览)

---

## 技术栈

| 层面 | 技术 |
|------|------|
| 语言 | Java 17（sealed interface, record, pattern matching） |
| Agent 框架 | LangChain4j 1.18.x |
| LLM 接入 | OpenAI-compatible API（DeepSeek / 小米 MiMo 等） |
| MCP 协议 | LangChain4j MCP 1.18.x-beta（Streamable HTTP） |
| Web 框架 | Spring Boot 3.4.x（REST + SSE + 异步线程池） |
| 序列化 | Jackson 2.18.x |
| 配置 | SnakeYAML（agent.yaml） |
| 日志 | SLF4J + Logback |
| HTML 解析 | Jsoup（web_fetch / web_search） |
| 构建 | Maven |

---

## 项目结构

```
eon-agent/
├── src/main/java/cn/kong/eon/
│   ├── EonAgentApplication.java          # Spring Boot 启动类
│   ├── agent/
│   │   ├── EonAgent.java                 # ★ Agent 统一引擎（Core Loop）
│   │   ├── TurnAction.java               # Turn 返回值（Continue / Exit）
│   │   ├── TurnCallback.java             # SSE 流式回调接口
│   │   ├── hook/                         # Hook 扩展体系
│   │   │   ├── Hook.java                 #   Hook 基础接口（4 阶段）
│   │   │   ├── HookResult.java           #   Hook 返回值（ok / stop）
│   │   │   ├── StopReason.java           #   停止原因（类别 + grace steps）
│   │   │   ├── StopCategory.java         #   停止类别枚举
│   │   │   ├── premodel/                 #   PreModel Hooks
│   │   │   │   ├── BudgetHook.java       #     预算检查
│   │   │   │   ├── ContextCompactHook.java #   上下文压缩
│   │   │   │   └── TodoNavigatorHook.java  #  Todo 导航渲染
│   │   │   ├── postmodel/
│   │   │   │   └── LoopDetectHook.java   #     循环检测
│   │   │   ├── pretool/
│   │   │   │   └── GateHook.java         #     门禁校验
│   │   │   └── posttool/
│   │   │       ├── CheckpointHook.java   #     Checkpoint 保存
│   │   │       └── FailureBreakerHook.java #   失败熔断
│   │   └── support/
│   │       ├── HookDispatcher.java       #   Hook 统一调度器
│   │       ├── ToolExecutionHandler.java #   工具执行处理器（并行）
│   │       ├── TurnLogger.java           #   Turn 日志器
│   │       └── TurnRecord.java           #   Turn 结构化日志记录
│   ├── api/
│   │   ├── controller/
│   │   │   ├── ChatController.java       #   对话 API（同步/异步/SSE/交互）
│   │   │   ├── SessionController.java    #   会话管理 API
│   │   │   └── HealthController.java     #   健康检查
│   │   ├── dto/                          #   请求/响应 DTO
│   │   ├── advice/
│   │   │   └── GlobalExceptionHandler.java # 全局异常处理
│   │   └── exception/
│   │       └── SessionBusyException.java
│   ├── bootstrap/
│   │   └── AgentBootstrap.java           # CLI 启动器
│   ├── config/
│   │   ├── AgentConfig.java              # 配置加载器（agent.yaml）
│   │   ├── AsyncConfig.java             # 异步线程池配置
│   │   └── SpringBeanConfig.java        # 全局 Spring Bean 装配
│   ├── context/
│   │   ├── ContextBuilder.java          # ★ 上下文分层构建器
│   │   ├── CompressionEngine.java       # ★ 三级压缩引擎
│   │   ├── PairingRepairer.java         #   tool_use/result 配对修复
│   │   └── dynamic/                     #   动态上下文注入
│   │       ├── UserInfoProvider.java
│   │       └── SkillsIndexProvider.java
│   ├── llm/
│   │   ├── LlmClient.java              # ★ LLM 客户端（重试+退避）
│   │   ├── LlmResponse.java            #   LLM 响应封装
│   │   └── LlmStalledException.java    #   LLM 不可用异常
│   ├── loop/
│   │   └── LoopDetector.java           # ★ 死循环检测器
│   ├── mcp/
│   │   └── McpClientManager.java       #   MCP 客户端管理器
│   ├── model/
│   │   ├── SessionState.java           # ★ 会话级运行时状态
│   │   ├── TokenUsage.java             #   Token 用量
│   │   ├── CompressionState.java       #   压缩状态
│   │   ├── TodoItem.java / TodoStatus.java
│   │   ├── Checkpoint.java             #   Checkpoint 模型
│   │   ├── ArtifactRef.java            #   Artifact 引用
│   │   ├── ToolExecutionResult.java
│   │   └── ToolPermission.java
│   ├── service/
│   │   ├── AgentService.java           # ★ Agent 核心服务层
│   │   ├── ChatJob.java                #   异步任务状态
│   │   ├── JobManager.java             #   异步任务管理器
│   │   ├── SseEmitterCallback.java     #   SSE 回调实现
│   │   └── SessionCleanupScheduler.java #  定时清理
│   ├── session/
│   │   ├── AgentBootstrapFactory.java  # ★ Agent 实例工厂
│   │   ├── AgentSession.java           # ★ 会话上下文容器
│   │   ├── SessionManager.java         #   会话生命周期管理
│   │   └── PendingInteraction.java     #   异步交互暂停状态
│   ├── store/
│   │   ├── JsonlStore.java            # ★ Append-Only 消息存储
│   │   ├── TodoStore.java             #   Todo 内存存储
│   │   ├── MemoryStore.java           #   跨会话记忆存储
│   │   ├── ArtifactStore.java         #   大文本落盘存储
│   │   └── CheckpointStore.java       #   Checkpoint 存储
│   ├── tool/
│   │   ├── ToolRegistry.java          # ★ 工具注册表（本地+MCP）
│   │   ├── ToolExecutor.java          #   工具执行器接口
│   │   ├── ToolDescriptor.java        #   工具描述符+Schema构建
│   │   ├── ToolOutcome.java           #   工具执行结果
│   │   ├── ToolResultRenderer.java    # ★ 工具结果渲染（Artifact）
│   │   ├── ToolContext.java           #   工具执行上下文
│   │   ├── ArgumentSanitizer.java     #   参数类型清洗
│   │   ├── PathResolver.java          #   路径解析+沙箱
│   │   ├── InteractionCallback.java   #   用户交互回调接口
│   │   ├── InteractionCallbackHolder.java # 延迟绑定 Holder
│   │   └── builtin/                   #   内置工具（11 个）
│   └── util/
│       ├── FileUtils.java
│       └── JsonMapper.java
├── src/main/resources/
│   ├── application.yml                # Spring Boot 配置
│   ├── config/agent.yaml              # ★ Agent 核心配置
│   ├── prompts/
│   │   ├── system_prompt.md           #   系统提示词
│   │   └── user_rules.md             #   用户规则模板
│   └── logback.xml
└── pom.xml
```

---

## 架构总览

```
                    ┌──────────────────────────────────────────────┐
                    │              EonAgentApplication              │
                    │         (Spring Boot / CLI 双模式)            │
                    └────────────────┬─────────────────────────────┘
                                     │
                    ┌────────────────┴────────────────┐
                    │           AgentService           │
                    │  (同步 / 异步 / SSE / 交互)      │
                    └────────────────┬────────────────┘
                                     │
                    ┌────────────────┴────────────────┐
                    │         AgentSession             │
                    │  (ReentrantLock + State + MCP)   │
                    └────────────────┬────────────────┘
                                     │
          ┌──────────────────────────┴──────────────────────────┐
          │                    EonAgent                           │
          │               (Agent Core Loop)                       │
          │                                                        │
          │  ┌──────────────────────────────────────────────┐     │
          │  │             executeTurn()                      │     │
          │  │                                                │     │
          │  │  1. buildContext() + PreModel Hooks            │     │
          │  │  2. ContextBuilder.build() → messages          │     │
          │  │  3. LlmClient.chat(messages, tools)            │     │
          │  │  4. 无工具调用 → Exit（任务完成）               │     │
          │  │  5. PostModel Hooks（循环检测）                 │     │
          │  │  6. Extension Loop:                             │     │
          │  │     PreTool → Execute → PostTool               │     │
          │  │  7. finalizeAndAppend（回填 JSONL）            │     │
          │  │  8. stop 期间消耗 grace step                   │     │
          │  └──────────────────────────────────────────────┘     │
          └────────────────────────────────────────────────────────┘
```

---

## 核心流程

### 1. Agent Core Loop（主循环）

`EonAgent.runStream()` 是整个系统的核心驱动，采用 **Core Loop + Extension Loop** 双循环设计：

```
runStream(state, callback)
  │
  ├── initRun: 日志启动 + 追加 UserMessage 到 JSONL
  │
  └── while (turnCount < maxSteps)
        │
        ├── executeTurn(state)
        │     │
        │   ┌─ 1. buildContext() ── 组装上下文（System Prompt + 动态注入块 + Transcript）
        │   │
        │   ├─ 2. firePreModelHooks() ── BudgetHook / TodoNavigatorHook / ContextCompactHook
        │   │     └── stop? → handleStop（注入收尾 nudge，进入 grace period）
        │   │
        │   ├─ 3. ContextBuilder.build() → messages
        │   │     LlmClient.chat(messages, toolSpecifications)
        │   │
        │   ├─ 4. 无工具调用?
        │   │     ├── finishReason=length → 截断纠正，Continue
        │   │     └── 否则 → Exit（任务完成，输出 LLM 文本）
        │   │
        │   ├─ 5. firePostModelHooks() ── LoopDetectHook
        │   │     └── stop? → handleStop / skip
        │   │
        │   ├─ 6. executeExtensionLoop()
        │   │     ├── firePreToolHooks() ── GateHook（破坏性工具门禁）
        │   │     ├── ToolExecutionHandler.execute() ── 并行/串行执行工具
        │   │     └── firePostToolHooks() ── FailureBreakerHook / CheckpointHook
        │   │
        │   ├─ 7. finalizeAndAppend() ── AI 消息 + 工具结果回填 JSONL
        │   │
        │   └─ 8. stop 期间? → consumeGraceStep（grace 耗尽 → 硬终止）
        │
        └── Turn 结束，回到 while 条件判断
```

**关键设计决策：**

- **无工具调用 = 任务完成**：LLM 返回纯文本（不调用工具）即视为任务完成，直接退出循环
- **TurnAction sealed interface**：`executeTurn` 返回 `Continue` 或 `Exit(output)`，消除 null 歧义
- **try-finally 保证 flush**：无论 Turn 是否异常，`flushTurn` 一定被执行
- **SSE 回调**：在关键节点调用 `TurnCallback`，callback 为 null 时退化为同步模式

### 2. 上下文构建与分层注入

`ContextBuilder` 负责分层组装发送给 LLM 的 messages 列表：

```
物理顺序（从上到下）：
┌─────────────────────────────────────────────────────────┐
│  System Prompt                    ← KV Cache 前缀稳定    │
│  (不拼接动态内容，保证 prefix caching)                    │
├─────────────────────────────────────────────────────────┤
│  Summary                          ← 历史对话摘要         │
│  (压缩后生成，5 段式结构)                                 │
├─────────────────────────────────────────────────────────┤
│  <user_info>                      ← OS / 日期 / 工作目录  │
│  <rules>                          ← 用户自定义规则       │
│  <memories>                       ← 跨会话记忆           │
│  <agent_skills>                   ← 技能索引             │
├─────────────────────────────────────────────────────────┤
│  Transcript                       ← 对话历史（JSONL 快照）│
│  (UserMessage / AiMessage / ToolExecutionResultMessage)  │
├─────────────────────────────────────────────────────────┤
│  Navigator                        ← Todo 列表（Pinned）   │
│  Runtime Nudges                   ← 运行时提醒（本轮有效） │
│  Tail Guard                       ← 尾部保护消息         │
└─────────────────────────────────────────────────────────┘
```

**动态注入块**在每轮由 `EonAgent.buildContext()` 填充：

| 注入块 | 来源 | 说明 |
|--------|------|------|
| `user_info` | `UserInfoProvider.generate()` | 操作系统、日期、时区、语言、工作目录 |
| `rules` | `prompts/user_rules.md` 外部文件 | 用户自定义规则，仅在外部文件存在且非空时注入 |
| `memories` | `MemoryStore.renderForInjection()` | 跨会话记忆列表 |
| `agent_skills` | `SkillsIndexProvider.generate()` | 技能索引目录 |
| `navigator` | `TodoNavigatorHook` | Todo 列表（`todo_write` 调用后激活） |
| `runtime_nudges` | `pendingNudges + formatCorrections` | 运行时提醒（预算告警、循环警告、格式纠正等） |

### 3. Hook 扩展体系

Hook 是 Agent 的核心扩展机制，在 Core Loop 的 4 个阶段插入自定义逻辑：

```
executeTurn 时间线：

    ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐
    │ PreModel│ →  │ LLM Call│ →  │PostModel│ →  │Extension│
    │  Hooks  │    │         │    │  Hooks  │    │  Loop   │
    └─────────┘    └─────────┘    └─────────┘    └────┬────┘
                                                      │
                                    ┌─────────┐  ┌───┴──────┐  ┌─────────┐
                                    │PreTool  │→│ Execute  │→│PostTool │
                                    │ Hooks   │  │ Tools   │  │ Hooks   │
                                    └─────────┘  └─────────┘  └─────────┘
```

**4 个 Hook 阶段：**

| 阶段 | 接口 | 触发时机 | 内置 Hook |
|------|------|----------|-----------|
| PreModel | `Hook.PreModelHook` | LLM 调用前，上下文组装后 | `BudgetHook`(order=10), `TodoNavigatorHook`(order=20), `ContextCompactHook`(order=100) |
| PostModel | `Hook.PostModelHook` | LLM 响应返回后，工具执行前 | `LoopDetectHook`(order=30) |
| PreTool | `Hook.PreToolHook` | 工具执行前 | `GateHook`(order=20) |
| PostTool | `Hook.PostToolHook` | 单个工具执行后 | `FailureBreakerHook`(order=30), `CheckpointHook`(order=100) |

**Hook 返回值（`HookResult`）：**

- `ok()` — 继续，一切正常
- `stop(StopReason)` — 请求优雅停止，由 `EonAgent.handleStop()` 决定是给 LLM 最后一次总结机会还是直接硬终止

**调度器（`HookDispatcher`）调度规则：**

- **PreModel 阶段**：stop 后 **继续遍历** 后续 hook（如 BudgetHook stop 后 ContextCompactHook 仍需执行）
- **其他阶段**：stop 后 **finalize + skip**，跳过后续 hook
- 调度返回三态 `FireResult`：`Continue` / `Skip` / `Exit(output)`

### 4. 上下文压缩（三级递进）

`ContextCompactHook`（PreModel, order=100）在每轮 LLM 调用前检查上下文水位，触发递进式压缩：

```
水位 = estimatedTokens / context.maxTokens

       0% ───────────────────────────────────────────────────► 100%
                          │          │            │
                    snip(65%)   prune(82%)  summarize(95%)
```

| 级别 | 水位阈值 | 动作 | 说明 |
|------|----------|------|------|
| **Snip** | ≥ 65% | 截短旧 tool result | 保留前 80 字符骨架 + artifact 引用标记 |
| **Prune** | ≥ 82% | 替换为占位符 | 保留 artifact 引用，内容替换为 `[旧工具结果内容已清除]` |
| **Summarize** | ≥ 95% | LLM 生成摘要 + 删旧消息 | 5 段式结构化摘要，删除被覆盖的旧消息 |

**两种触发模式：**

- **水位触发**（water-triggered）：上下文 token 超过阈值时触发，走完整 Snip → Prune → Summarize 递进
- **轮数触发**（turn-triggered）：距上次压缩 ≥ `summarize_turns` 轮时触发，仅执行 Snip（+ Prune 如水位达标），不执行 Summarize（避免昂贵的 LLM 调用）

**Tail Guard 保护：** 最近 `TAIL_GUARD_MIN_TURNS`（3）轮的消息（约 `turns*2+2` 条）不被压缩。

**PairingRepairer 配对修复：** Summarize 删除旧消息后可能导致 `tool_use / tool_result` 配对断裂。`PairingRepairer` 负责修复：
- 匹配的 tool_result 移到对应 tool_use 之后
- 丢弃孤立的 tool_result
- 为缺失结果的 tool_use 插入合成错误消息
- 按 tool_use_id 去重

**增量摘要：** 旧摘要 + 被裁剪对话一起送 LLM 重生成（非字符串拼接），摘要中包含原始 transcript 文件路径供回溯。

### 5. 工具系统

#### 工具注册

`ToolRegistry` 统一管理本地工具和 MCP 工具：

- **本地工具**：通过 `ToolDescriptor` 注册，受白名单过滤
- **MCP 工具**：通过 `McpClientManager` 连接 MCP 服务后自动注册，不受本地白名单限制
- 每个工具携带 `ToolPermission`（READONLY / RESTRICTED_WRITE / DESTRUCTIVE）

#### 工具执行

`ToolExecutionHandler` 封装工具执行全流程：

```
execute(requests)
  │
  ├── 单请求 → 直接执行
  │
  └── 多请求 → 分区执行
        ├── 串行豁免清单（todo_write / AskQuestion）→ 强制串行
        └── 其他工具 → 并行执行（FixedThreadPool, parallelism=4）
              └── 单工具失败不影响其他工具（异常隔离）
```

**executeSingle 流程：**
1. 熔断检查 → 被熔断的工具返回合成错误
2. 参数解析（JSON → Map）
3. `ArgumentSanitizer` 参数类型清洗（根据 Schema 转换 LLM 不规范的参数）
4. `ToolRegistry.execute()` 执行工具
5. `ToolResultRenderer.render()` 渲染结果
6. `todo_write` 后处理：激活 TodoNavigator + 记录快照用于无进展检测

#### 工具结果渲染

`ToolResultRenderer` 处理大文本工具结果：

- ≤ 3000 字符：完整内容直接写入消息
- > 3000 字符：原文落盘为 **Artifact**（`artifact://art_001`），消息只保留摘要（前 700 + 后 300 字符）+ 引用

#### 路径沙箱

`PathResolver` 统一处理路径解析：
- 相对路径基于 `workspace` 目录解析
- 沙箱开启时，解析后路径必须在 workspace 内（禁止 `..` 穿越逃逸）

### 6. 优雅停止与 Grace Period

当 Hook 返回 `stop(StopReason)` 时，触发优雅停止流程：

```
Hook 检测到停止条件
  │
  ├── graceSteps = 0 → 立即硬终止（forceTerminate）
  │
  └── graceSteps > 0 → 进入 Grace Period
        │
        ├── 首次 stop:
        │     ├── state.stopState.request(reason)
        │     ├── 注入收尾 nudge（引导 LLM 输出总结）
        │     └── Continue（给 LLM graceSteps 轮整理机会）
        │
        ├── stop 期间 LLM 未调用工具 → Exit（已输出总结）
        │
        └── stop 期间 LLM 仍调用工具 → consumeGraceStep
              ├── 还有 grace → Continue
              └── grace 耗尽 → 硬终止（forceTerminate）
```

**停止类别（`StopCategory`）：**

| 类别 | 触发 Hook | 说明 |
|------|-----------|------|
| `BUDGET_EXCEEDED` | BudgetHook | Token 预算硬超限 |
| `LOOP_DETECTED` | LoopDetectHook | 同工具同参数重复调用超阈值 |
| `GATE_REJECTED` | GateHook | 破坏性工具缺少必填参数 |
| `FAILURE_BREAKER` | — | 失败熔断（仅 nudge，不触发会话级停止） |
| `MAX_STEPS_REACHED` | — | 达到最大步数限制 |
| `UNEXPECTED_ERROR` | — | 执行异常 |

**硬终止输出格式：**
```
任务终止: <停止类别>
原因: <可读消息>
消耗: <totalTokens> tokens, <turnCount> 轮
```

### 7. 死循环检测与熔断

`LoopDetector` 提供三种检测：

| 检测类型 | 触发位置 | 逻辑 | 告警阈值 | 停止阈值 |
|----------|----------|------|----------|----------|
| **重复调用** | PostModel (`LoopDetectHook`) | 同工具+同参数连续调用 | 3 次 → nudge 警告 | 5 次 → 优雅停止 |
| **无进展** | PostTool (`ToolExecutionHandler`) | Todo 快照连续 N 步无变化 | 6 步 → nudge 警告 | — |
| **单工具熔断** | PostTool (`FailureBreakerHook`) | 单个工具连续失败 | 3 次 → nudge 警告 | 5 次 → 熔断该工具 |

**熔断 vs 会话级停止的区别：**

- 熔断（`trippedTools`）：仅阻止被熔断的工具执行，其他工具仍可正常运行，返回 `HookResult.ok()` + nudge
- 会话级停止：触发优雅停止流程，可能导致整个 Agent 终止

**成功调用重置计数：** 工具成功执行后，重置该工具的失败计数和指纹计数。

### 8. 会话管理与生命周期

```
┌─────────────────────────────────────────────────────────┐
│                     AgentService                         │
│                                                          │
│  createSession() ──→ AgentBootstrapFactory.createSession │
│                          │                               │
│     ┌────────────────────┼───────────────────────┐      │
│     │                AgentSession                 │      │
│     │                                            │      │
│     │  ┌──────────┐  ┌────────────┐  ┌────────┐ │      │
│     │  │ EonAgent │  │SessionState│  │ MCP    │ │      │
│     │  │ (引擎)   │  │ (状态)     │  │Manager │ │      │
│     │  └──────────┘  └────────────┘  └────────┘ │      │
│     │                                            │      │
│     │  ReentrantLock (会话级串行)                 │      │
│     │  PendingInteraction (异步交互)              │      │
│     │  lastActiveAt (超时清理依据)                │      │
│     └────────────────────────────────────────────┘      │
│                          │                               │
│              SessionManager (ConcurrentHashMap)          │
│                          │                               │
│         SessionCleanupScheduler (每5分钟)                │
│         清理 >30分钟未活跃的会话                          │
└─────────────────────────────────────────────────────────┘
```

**会话级串行锁：** `ReentrantLock` 保证同一会话不会并发执行 Agent（`tryLock()` 非阻塞，失败抛 `SessionBusyException`）。

**AgentBootstrapFactory 组装流程：**
1. 创建会话级 Store（TodoStore / MemoryStore / ArtifactStore / JsonlStore / CheckpointStore）
2. 创建 ToolRegistry + 注册本地工具 + 连接 MCP 服务
3. 创建 ToolContext（含 InteractionCallbackHolder 延迟绑定）
4. 加载系统提示词
5. 创建 EonAgent + 挂载 7 个 Hook
6. 尝试从 Checkpoint 恢复（如果启用）
7. 创建 AgentSession + 绑定 InteractionCallback

### 9. HTTP API 与 SSE 流式推送

#### API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/chat` | 同步对话（阻塞直到完成） |
| POST | `/api/v1/chat/async` | 异步对话（返回 jobId） |
| GET | `/api/v1/chat/jobs/{jobId}` | 查询异步任务状态 |
| GET | `/api/v1/stream?message=xxx` | SSE 流式对话 |
| GET | `/api/v1/chat/{sessionId}/interaction` | 查询交互状态 |
| POST | `/api/v1/chat/{sessionId}/answer` | 提交用户答案 |
| POST | `/api/v1/sessions` | 创建会话 |
| GET | `/api/v1/sessions` | 列出会话 |
| GET | `/api/v1/sessions/{id}` | 获取会话信息 |
| DELETE | `/api/v1/sessions/{id}` | 关闭会话 |

#### SSE 事件类型

`TurnCallback` 在 Agent 执行的关键节点被调用，经 `SseEmitterCallback` 转换为 SSE 事件：

| 事件类型 | 触发时机 | 携带数据 |
|----------|----------|----------|
| `RUN_START` | Agent 开始运行 | sessionId |
| `TURN_START` | 每个 Turn 开始 | turn |
| `LLM_RESPONSE` | LLM 响应到达 | thought, toolNames |
| `TOOL_START` | 工具开始执行 | toolName |
| `TOOL_RESULT` | 工具执行完成 | toolName, success, summary |
| `TURN_END` | Turn 结束 | turn, totalTokens |
| `DONE` | 正常完成 | output, turn, totalTokens |
| `TERMINATED` | 被强制终止 | reason, turn, totalTokens |
| `ERROR` | 执行出错 | error |

**异步执行：** Agent 在专用线程池 `agentExecutor`（核心 4 / 最大 16 / 队列 50）中执行，不阻塞 Tomcat 请求线程。SSE 超时 5 分钟。

### 10. 异步交互（AskQuestion）

当 Agent 执行过程中调用 `AskQuestion` 工具向用户收集信息时，触发异步交互流程：

```
Agent 线程                          HTTP 线程
   │                                    │
   ├── AskQuestionTool.execute()        │
   │     │                              │
   │   InteractionCallback.askQuestions │
   │     │                              │
   │   PendingInteraction.setPending()  │
   │   (进入 PENDING 状态)               │
   │     │                              │
   │   awaitAnswer() ──阻塞──           ├── GET /interaction → 返回问题
   │   (CompletableFuture.get)          │
   │     │                              ├── POST /answer → 提交答案
   │     │                              │
   │   ←──── answerFuture.complete() ───┘
   │     │
   │   reset() (回到 IDLE)
   │   返回用户答案
   │                                    │
   ├── Agent 继续执行                    │
```

**关键设计：**

- **CompletableFuture 阻塞**：Agent 线程在 `awaitAnswer()` 中阻塞，超时 10 分钟自动恢复（返回空 Map）
- **自动 reset**：Agent 线程获取答案后自行 reset，重新创建 future，支持多次交互
- **延迟绑定**：`InteractionCallbackHolder` 解决 ToolContext 需在 EonAgent 前创建但 InteractionCallback 需 AgentSession 实例的循环依赖
- **CLI 模式**：不使用此机制，直接从 stdin 读取

### 11. 持久化与崩溃恢复

#### 持久化层

| Store | 存储位置 | 格式 | 说明 |
|-------|----------|------|------|
| `JsonlStore` | `{sessionId}/transcript.jsonl` | Append-Only JSONL | 原始消息账本，永不修改已有消息 |
| `ArtifactStore` | `{sessionId}/artifacts/` | TXT 文件 | 大文本工具结果落盘 |
| `CheckpointStore` | `{sessionId}/checkpoints/` | JSON 文件 | Todo 快照 + Token 用量 + 压缩状态 |
| `MemoryStore` | `{baseDir}/memories/` | JSON 文件 | 跨会话记忆，所有会话共享 |

#### Checkpoint 恢复

当 `mode.checkpoint_enabled = true` 时：

1. **保存**：`CheckpointHook`（PostTool）在 `todo_write` 成功后保存快照（Todo 列表 + Token 用量 + 压缩状态）
2. **恢复**：`AgentBootstrapFactory.createSession()` 创建会话时尝试加载最新 Checkpoint，恢复 TodoStore 状态

#### 记忆引用

Agent 最终输出中的 `[[memory:mem_xxxx]]` 引用会被 `MemoryStore.renderReferences()` 替换为 `标题（内容摘要）` 格式。

### 12. MCP 协议集成

通过 LangChain4j MCP 客户端接入外部工具服务：

```
agent.yaml 配置
  │
  └── mcp.servers
        └── novel-mcp-server:
              url: "http://localhost:8081/mcp"
              enabled: true
              permission: "READONLY"

AgentBootstrapFactory.connectMcpServers()
  │
  ├── McpClientManager.connect()
  │     └── StreamableHttpMcpTransport → DefaultMcpClient
  │
  ├── McpClientManager.listTools()
  │     └── 获取 MCP 服务提供的 ToolSpecification 列表
  │
  └── ToolRegistry.registerMcpTools()
        └── 注册到 mcpToolSpecs（不受白名单限制）
```

**MCP 工具执行：** `ToolRegistry.execute()` 判断工具来源，MCP 工具委托 `McpClientManager.executeTool()` 调用远程服务。

**会话级隔离：** 每个会话创建独立的 MCP 连接，会话销毁时关闭连接。

---

## 配置说明

核心配置文件 `src/main/resources/config/agent.yaml`：

```yaml
# LLM 配置
llm:
  provider: "mimo"                          # 提供商标识
  base_url: "https://api.example.com/v1"    # API 地址
  api_key: "${LLM_API_KEY}"                 # 环境变量引用（支持 ${VAR} 和 ${VAR:-default}）
  model_name: "mimo-v2.5"
  temperature: 0.7
  timeout: 120                              # 请求超时（秒）
  max_tokens: 12800                         # 单次响应最大输出 token

# 上下文窗口
context:
  max_tokens: 120000
  budget_ratio: 0.7
  compression:
    snip_threshold: 0.65      # ≥65% 截短旧工具结果
    prune_threshold: 0.82     # ≥82% 替换为占位符
    summarize_threshold: 0.95 # ≥95% LLM 摘要并删旧消息

# 会话级 Token 预算
budget:
  max_tokens: 10000000        # 累计 token 上限
  soft_threshold: 0.75        # 75% 注入收尾 nudge
  hard_threshold: 1.0         # 100% 触发优雅停止
  grace_steps: 3              # 触发后额外轮次

# 循环限制
loop:
  max_steps: 100              # 正常模式最大步数
  absolute_max_steps: 160     # stop 期间绝对上限

# 死循环检测
loop_detect:
  repeat_warn: 3              # 同工具同参数重复 3 次告警
  repeat_stop: 5              # 重复 5 次判定死循环
  no_progress_steps: 6        # Todo 连续 6 步无变化告警
  failure_warn: 3             # 单工具连续失败 3 次注入警告
  failure_stop: 5             # 单工具连续失败 5 次熔断

# LLM 调用重试
retry:
  attempts: 3                 # 最大重试次数
  min_delay_ms: 500           # 退避起始延迟
  max_delay_ms: 5000          # 退避最大延迟
  jitter: 0.2                 # 抖动系数

# 存储
storage:
  base_dir: "./data"          # 会话数据根目录: base_dir/{sessionId}/

# 工具
tools:
  whitelist: [...]            # 允许注册的本地工具白名单
  destructive: ["delete_file"] # 破坏性工具
  sandbox_enabled: true       # 路径沙箱开关
  parallelism: 4              # 并行工具执行线程数

# 压缩
compression:
  summarize_turns: 6          # 轮数触发压缩阈值

# MCP 服务
mcp:
  servers:
    novel-mcp-server:
      url: "http://localhost:8081/mcp"
      enabled: true
      permission: "READONLY"
```

Spring Boot 层面配置 `src/main/resources/application.yml`：

```yaml
server:
  port: 8080
  tomcat:
    connection-timeout: 5000
    keep-alive-timeout: 60000

spring:
  mvc:
    async:
      request-timeout: 300000    # 异步请求超时 5 分钟

eon:
  session:
    timeout-minutes: 30          # 会话超时：30 分钟无活动自动清理
```

---

## 快速启动

### 环境要求

- JDK 17+
- Maven 3.8+
- 一个 OpenAI-compatible LLM API（DeepSeek / 小米 MiMo 等）

### 1. 配置环境变量

```bash
# LLM API Key
export LLM_API_KEY="your-api-key"

# 百度千帆搜索 API Key（web_search 工具使用，可选）
export QIANFAN_API_KEY="your-qianfan-api-key"
```

### 2. 编译

```bash
mvn clean package -DskipTests
```

### 3. 启动 — API 模式（Spring Boot）

```bash
java -jar target/eon-agent-1.0.0-SNAPSHOT.jar
```

启动后访问 `http://localhost:8080`，使用 REST API 与 Agent 交互。

### 4. 启动 — CLI 模式

```bash
java -cp target/eon-agent-1.0.0-SNAPSHOT.jar cn.kong.eon.bootstrap.AgentBootstrap
```

在命令行中输入问题或任务，Agent 执行完毕后输出结果。

### 5. 快速测试

```bash
# 创建会话并同步对话
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "帮我搜索一下今天的天气"}'

# SSE 流式对话
curl -N "http://localhost:8080/api/v1/stream?message=帮我写一个Python脚本"

# 异步对话
curl -X POST http://localhost:8080/api/v1/chat/async \
  -H "Content-Type: application/json" \
  -d '{"message": "帮我分析这段代码"}'
# → 返回 jobId，轮询 GET /api/v1/chat/jobs/{jobId} 查询状态
```

---

## 内置工具一览

| 工具名 | 权限 | 说明 |
|--------|------|------|
| `read_file` | READONLY | 读取文件内容（支持行号范围） |
| `write` | RESTRICTED_WRITE | 写入文件（覆盖或新建） |
| `delete_file` | DESTRUCTIVE | 删除文件（门禁校验必填 `target_file`） |
| `list_dir` | READONLY | 列出目录内容 |
| `download_file` | RESTRICTED_WRITE | 从 URL 下载文件到 workspace |
| `grep` | READONLY | 正则搜索文件内容 |
| `todo_write` | — | 任务管理（全量替换/合并 Todo 列表） |
| `AskQuestion` | READONLY | 向用户提问（异步交互） |
| `update_memory` | RESTRICTED_WRITE | 跨会话记忆管理（创建/更新/删除） |
| `web_fetch` | READONLY | 抓取网页内容（HTML → Markdown） |
| `web_search` | READONLY | 网络搜索（百度千帆 AI Search） |

**MCP 工具**：根据 `agent.yaml` 中配置的 MCP 服务动态注册，不受白名单限制。
