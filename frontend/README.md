# Eon Agent Frontend

基于 Vue 3 + Vite 构建的现代化 Agent 智能体工作台前端，为 [eon-agent](https://github.com/ricezong/eon-agent) 后端提供完整的 Web UI。

## ✨ 特性

- 🎨 **现代 Web UI 美学**：深色玻璃拟态 + 紫蓝渐变光晕 + 精致圆角细节
- ⌨️ **打字机效果**：AI 回复逐字渲染，模拟自然节奏，支持流式追加
- 📡 **全量 SSE 事件支持**：覆盖后端 9 种 AgentEvent（RUN_START / TURN_START / LLM_RESPONSE / TOOL_START / TOOL_RESULT / TURN_END / DONE / TERMINATED / ERROR）
- 🛠️ **工具调用可视化**：每个工具调用独立卡片展示，含图标、状态、结果摘要
- 💭 **思考过程展示**：LLM 思考文本 + 计划调用工具列表实时呈现
- ❓ **异步交互**：支持 AskQuestion 工具触发的 PENDING 状态，用户可提交结构化答案
- 📋 **会话管理**：侧边栏会话列表，支持新建、切换、删除
- 🎈 **Markdown 渲染**：代码高亮、表格、引用等完整支持
- 📱 **响应式布局**：自适应不同屏幕尺寸

## 🚀 快速开始

```bash
# 安装依赖
npm install

# 启动开发服务器（默认 5173 端口）
npm run dev

# 构建生产版本
npm run build

# 预览生产构建
npm run preview
```

启动后访问 http://localhost:5173

> 前端默认通过 Vite proxy 将 `/api` 请求转发到 `http://localhost:8080`，请确保后端 eon-agent 已启动。

## 🧪 测试模式

访问 `http://localhost:5173/?test=1` 可进入测试模式，前端会注入模拟的对话数据（含思考、工具调用、打字机效果），无需后端即可预览完整 UI 效果。

## 📁 项目结构

```
src/
├── api/
│   ├── index.js          # REST API 封装（会话、对话、交互）
│   └── sse.js            # SSE 客户端（fetch + ReadableStream）
├── components/
│   ├── Sidebar.vue       # 侧边栏（Logo + 会话列表）
│   ├── MessageBubble.vue # 消息气泡（含打字机效果）
│   ├── ThoughtBlock.vue  # LLM 思考过程展示
│   ├── ToolBubble.vue    # 工具调用卡片
│   ├── InputArea.vue     # 输入框 + 发送
│   └── InteractionPanel.vue # AskQuestion 交互面板
├── composables/
│   └── useTypewriter.js  # 打字机效果 composable
├── stores/
│   └── app.js            # 全局响应式状态
├── styles/
│   └── global.css        # 设计系统（CSS 变量 + 全局样式）
├── utils/
│   ├── markdown.js       # Markdown 渲染 + 工具函数
│   └── tools.js          # 工具元数据（图标、颜色、分类）
├── App.vue               # 主应用
├── TestMode.vue          # 测试模式数据注入
└── main.js               # 入口
```

## 🔌 后端 API 对接

### REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/v1/health` | 健康检查 |
| POST | `/api/v1/sessions` | 创建会话 |
| GET  | `/api/v1/sessions` | 列出会话 |
| GET  | `/api/v1/sessions/{id}` | 获取会话详情 |
| DELETE | `/api/v1/sessions/{id}` | 关闭会话 |
| POST | `/api/v1/chat` | 同步对话 |
| POST | `/api/v1/chat/async` | 异步对话 |
| GET  | `/api/v1/chat/jobs/{jobId}` | 查询异步任务状态 |
| GET  | `/api/v1/chat/{sessionId}/interaction` | 查询交互状态 |
| POST | `/api/v1/chat/{sessionId}/answer` | 提交交互答案 |

### SSE 流式对话

```
GET /api/v1/stream?sessionId={可选}&message={必填}
```

响应为 `text/event-stream`，事件格式：

```
event: <EventType>
data: <AgentEvent JSON>
```

### SSE 事件类型

| 事件类型 | 字段 | 说明 |
|----------|------|------|
| `RUN_START` | sessionId | Agent 开始运行 |
| `TURN_START` | turn | Turn 开始 |
| `LLM_RESPONSE` | turn, thought, toolNames | LLM 响应（思考 + 工具调用） |
| `TOOL_START` | turn, toolName | 工具开始执行 |
| `TOOL_RESULT` | turn, toolName, success, summary | 工具执行完成 |
| `TURN_END` | turn, totalTokens | Turn 结束 |
| `DONE` | sessionId, output, turnCount, totalTokens | 正常完成 |
| `TERMINATED` | sessionId, error, turnCount, totalTokens | 强制终止 |
| `ERROR` | sessionId, error | 出错 |

## 🎨 设计系统

- **主色**：紫蓝渐变（#6366f1 → #a855f7）
- **背景**：深色基底（#0a0a0f）+ 径向光晕
- **玻璃拟态**：半透明背景 + backdrop-blur
- **圆角**：统一 8-16px
- **字体**：系统字体 + JetBrains Mono（代码）

## 🛠️ 技术栈

- Vue 3.5（Composition API + `<script setup>`）
- Vite 6
- marked + DOMPurify（Markdown 渲染）
- highlight.js（代码高亮）
- 原生 fetch + ReadableStream（SSE 解析，无需 EventSource polyfill）
