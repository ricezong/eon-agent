# Eon Agent

> 基于 Java + LangChain4j 的通用 AI Agent，支持聊天和任务执行，采用"单入口 + 能力插拔 + 统一上下文"架构。

## 架构概览

```
┌─────────────────────────────────────────────────────────┐
│                    EonAgent                              │
│  ┌──────────────────────────────────────────────────┐   │
│  │  Core Loop（核心循环）                            │   │
│  │  1. PolicyRouter 路由 Profile                     │   │
│  │  2. ContextBuilder 组装上下文                    │   │
│  │  3. beforeModelCall（能力模块前置处理）           │   │
│  │  4. ctx.build() 构建最终 messages                │   │
│  │  5. 调用 LLM                                     │   │
│  │  6. 解析输出                                     │   │
│  │  7. 无 tool_calls → 返回 / 有 tool_calls → 扩展  │   │
│  └──────────────────────────────────────────────────┘   │
│                    ↓                                     │
│  ┌──────────────────────────────────────────────────┐   │
│  │  Extension Loop（扩展循环）                       │   │
│  │  1. GateKeeper 门禁校验                          │   │
│  │  2. 执行工具                                     │   │
│  │  3. afterToolExecution（能力模块后置处理）       │   │
│  │  4. 回填 JSONL                                   │   │
│  │  5. 回到 Core Loop                               │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  能力模块（按优先级排序执行）：                          │
│  ├─ BudgetGuard (HIGH)        预算守卫                  │
│  ├─ ContextCompactor (NORMAL)  上下文压缩                │
│  ├─ LoopGuard (NORMAL)         循环守卫                  │
│  ├─ GateKeeperCapability (NORMAL) 门禁校验             │
│  ├─ TodoNavigator (NORMAL)     Todo 导航（按需激活）    │
│  └─ CheckpointManager (LOW)    Checkpoint（配置启用）   │
└─────────────────────────────────────────────────────────┘
```

## 核心设计

### 1. 单入口，无模式区分

不区分"聊天模式"和"Agent 模式"，由 `PolicyRouter` 根据用户输入自动路由：

| Profile | 触发条件 | 工具 Schema | 能力模块 |
|---|---|---|---|
| LIGHT_CHAT | 短输入 + 无工具触发词 | 不注入 | 基础层（压缩/预算/循环守卫） |
| ASSISTED | 含工具触发词（搜索/读取等） | 只注入网络搜索相关 | 基础层 |
| TASK_MULTI | LLM 调用过 todo_write | 全量注入 | 基础层 + TodoNavigator |

### 2. System Prompt 冻结（KV Cache 友好）

System Prompt 只包含 `basePrompt`，**不拼接任何动态内容**。`tool_catalog` 作为独立消息注入（放在 transcript 之后），保证 System Prompt 前缀稳定，每轮调用都能命中 KV Cache。

### 3. 上下文分层组装

```
messages[0]          System Prompt（basePrompt，完全冻结）
messages[1]?         Summary（压缩后才有）
messages[2..N]       Transcript（历史消息，可被压缩）
messages[N+1]?       ToolCatalog（ASSISTED/TASK_MULTI 才注入）
messages[N+2]?       Navigator（TodoNavigator 激活后才有）
messages[N+3..End]   TailGuard（尾部保护区，最近 3 轮）
```

### 4. 能力模块按优先级排序

| 优先级 | 模块 | 说明 |
|---|---|---|
| HIGH(1) | BudgetGuard | 先检查预算，超限则终止 |
| NORMAL(2) | ContextCompactor | 压缩上下文（Snip/Prune/Summarize） |
| NORMAL(2) | LoopGuard | 死循环检测 + 熔断器 |
| NORMAL(2) | GateKeeperCapability | 破坏性工具前置校验 |
| NORMAL(2) | TodoNavigator | 渲染 Todo+Insights（LLM 调 todo_write 后激活） |
| LOW(3) | CheckpointManager | 保存快照（配置启用时） |

### 5. 配对修复按需执行

- Snip/Prune 只截短 content，不删除消息，**不破坏配对**
- 只有 Summarize（水位 ≥ 95%）会删除旧消息替换为摘要，**才需要配对修复**

## 快速开始

### 环境要求

- Java 17+
- Maven 3.9+
- DeepSeek API Key（LLM）
- 百度千帆 API Key（web_search，可选）

### 配置

编辑 `src/main/resources/config/agent.yaml`：

```yaml
llm:
  provider: "deepseek"
  base_url: "https://api.deepseek.com/v1"
  api_key: "${DEEPSEEK_API_KEY}"
  model_name: "deepseek-chat"

web_search:
  api_key: "${QIANFAN_API_KEY}"
  search_source: "baidu_search_v2"
  top_k: 10

mcp:
  servers:
    novel-mcp-server:
      url: "http://124.223.110.114:8081/mcp"
      enabled: true
      permission: "READONLY"
```

### 运行

```bash
# 设置环境变量
export DEEPSEEK_API_KEY=your_key
export QIANFAN_API_KEY=your_key

# 编译
mvn clean compile

# 运行
mvn exec:java -Dexec.mainClass="cn.kong.eon.bootstrap.AgentMain"
```

### 测试

```bash
# 运行所有测试
mvn test

# 运行核心逻辑测试（不需要 API Key）
mvn test -Dtest=CoreLogicTest
```

## 项目结构

```
src/main/java/cn/kong/eon/
├── agent/                    # Agent 核心
│   ├── EonAgent.java         # 单入口统一引擎
│   ├── capability/           # 能力模块（可插拔）
│   │   ├── CapabilityModule.java
│   │   ├── BudgetGuard.java
│   │   ├── ContextCompactor.java
│   │   ├── LoopGuard.java
│   │   ├── GateKeeperCapability.java
│   │   ├── TodoNavigator.java
│   │   └── CheckpointManager.java
│   ├── context/              # 上下文构建
│   │   └── ContextBuilder.java
│   └── profile/              # 策略路由
│       ├── RequestProfile.java
│       └── PolicyRouter.java
├── bootstrap/                # 启动入口
│   ├── AgentBootstrap.java
│   └── AgentMain.java
├── config/                   # 配置加载
│   └── AgentConfig.java
├── context/                  # 压缩引擎
│   ├── CompressionEngine.java
│   └── PairingRepairer.java
├── llm/                      # LLM 客户端
│   ├── LlmClient.java
│   └── LlmResponse.java
├── loop/                     # 循环检测
│   └── LoopDetector.java
├── mcp/                      # MCP 客户端
│   └── McpClientManager.java
├── model/                    # 数据模型
│   ├── SessionState.java
│   ├── TodoItem.java
│   ├── CompressionState.java
│   └── ...
├── store/                    # 持久化
│   ├── JsonlStore.java
│   ├── ArtifactStore.java
│   ├── CheckpointStore.java
│   └── ...
└── tool/                     # 工具系统
    ├── ToolRegistry.java
    ├── ToolDescriptor.java
    └── builtin/             # 内置工具
        ├── TodoWriteTool.java
        ├── WebSearchTool.java
        └── ...
```

## 内置工具

| 工具 | 权限 | 说明 |
|---|---|---|
| web_search | READONLY | 百度千帆 AI Search |
| web_read | READONLY | 读取网页内容（Jsoup） |
| finish | RESTRICTED_WRITE | 结束任务 |
| todo_write | RESTRICTED_WRITE | 创建/更新任务清单 |
| todo_read | READONLY | 读取任务清单 |
| working_memory | RESTRICTED_WRITE | 记录关键发现 |
| download | DESTRUCTIVE | 下载文件 |

## 能力扩展

实现 `CapabilityModule` 接口并注册到 EonAgent：

```java
public class MyCapability implements CapabilityModule {
    @Override
    public String name() { return "MyCapability"; }

    @Override
    public boolean isActive(SessionState state) { return true; }

    @Override
    public int priority() { return Priority.NORMAL; }

    @Override
    public void beforeModelCall(SessionState state, ContextBuilder ctx) {
        // 在模型调用前注入逻辑
    }
}

// 注册
agent.addCapability(new MyCapability());
```

## 技术栈

- Java 17
- LangChain4j 1.18.0（核心）+ 1.18.1-beta28（MCP）
- Maven 3.9
- Jackson（JSON）
- Jsoup（HTML 解析）
- SLF4J + Logback（日志）
- SnakeYAML（配置）
