<script setup>
import { onMounted } from 'vue'
import { store, addMessage } from './stores/app'

// 测试模式：注入模拟数据验证 UI
onMounted(() => {
  // 设置一个模拟会话
  store.currentSessionId = 'test-session-001'
  store.sessions = [
    { sessionId: 'test-session-001', createdAt: '2026-08-24T10:00:00Z', lastActiveAt: '2026-08-24T11:30:00Z', turnCount: 2 },
    { sessionId: 'test-session-002', createdAt: '2026-08-24T09:00:00Z', lastActiveAt: '2026-08-24T09:45:00Z', turnCount: 5 },
    { sessionId: 'test-session-003', createdAt: '2026-08-23T14:00:00Z', lastActiveAt: '2026-08-23T18:20:00Z', turnCount: 8 },
  ]
  // 模拟其他会话的消息条数（当前会话会自动从 messages.length 计算）
  store.sessionMessageCounts = {
    'test-session-002': 8,
    'test-session-003': 12,
  }
  store.running = false
  addMessage({
    id: 1,
    role: 'user',
    content: '帮我搜索一下最新的 AI Agent 框架，并写一份简要的对比报告',
    status: 'done',
    events: [],
  })

  // 模拟助手消息（含思考、工具调用、最终输出）
  const assistantMsg = {
    id: 2,
    role: 'assistant',
    content: '',
    status: 'running',
    events: [],
    turn: 0,
    tokens: 0,
  }
  addMessage(assistantMsg)

  // 模拟事件序列
  setTimeout(() => {
    assistantMsg.events.push({
      type: 'TURN_START',
      turn: 1,
      timestamp: Date.now(),
    })
    assistantMsg.turn = 1
  }, 300)

  setTimeout(() => {
    assistantMsg.events.push({
      type: 'LLM_RESPONSE',
      turn: 1,
      thought: '用户需要搜索 AI Agent 框架并写对比报告。我应该先用 web_search 搜索最新信息，然后用 write 工具保存报告。',
      toolNames: ['web_search', 'write'],
      timestamp: Date.now(),
    })
  }, 800)

  setTimeout(() => {
    assistantMsg.events.push({
      type: 'TOOL_START',
      turn: 1,
      toolName: 'web_search',
      timestamp: Date.now(),
    })
  }, 1400)

  setTimeout(() => {
    assistantMsg.events.push({
      type: 'TOOL_RESULT',
      turn: 1,
      toolName: 'web_search',
      success: true,
      summary: '搜索完成，共找到 10 条结果：\n1. LangChain - 最流行的 LLM 框架\n2. AutoGPT - 自主任务执行\n3. CrewAI - 多 Agent 协作\n4. Microsoft AutoGen - 多 Agent 对话',
      timestamp: Date.now(),
    })
  }, 2200)

  setTimeout(() => {
    assistantMsg.events.push({
      type: 'TOOL_START',
      turn: 1,
      toolName: 'write',
      timestamp: Date.now(),
    })
  }, 2800)

  setTimeout(() => {
    assistantMsg.events.push({
      type: 'TOOL_RESULT',
      turn: 1,
      toolName: 'write',
      success: true,
      summary: '文件写入成功: agent-comparison.md（45 行）',
      timestamp: Date.now(),
    })
  }, 3400)

  setTimeout(() => {
    assistantMsg.events.push({
      type: 'TURN_END',
      turn: 1,
      totalTokens: 3580,
      timestamp: Date.now(),
    })
    assistantMsg.tokens = 3580
  }, 3800)

  setTimeout(() => {
    assistantMsg.content = `## AI Agent 框架对比报告

我已为您搜索并整理了当前主流的 AI Agent 框架，以下是简要对比：

### 1. LangChain
- **定位**：最流行的 LLM 应用开发框架
- **优势**：生态丰富、社区活跃、文档完善
- **劣势**：抽象层较重，复杂场景下性能一般

### 2. AutoGPT
- **定位**：自主任务执行 Agent
- **优势**：能够自主拆解任务、循环执行
- **劣势**：稳定性较差，容易陷入死循环

### 3. CrewAI
- **定位**：多 Agent 协作框架
- **优势**：角色分工清晰，适合复杂工作流
- **劣势**：配置较复杂

### 4. Microsoft AutoGen
- **定位**：多 Agent 对话框架
- **优势**：微软背书，企业级支持
- **劣势**：学习曲线陡峭

---

报告已保存至 \`agent-comparison.md\`，共 45 行。如需更详细的某个框架分析，请告诉我。`
    assistantMsg.status = 'done'
  }, 4200)

  // 模拟第二条用户消息
  setTimeout(() => {
    addMessage({
      id: 3,
      role: 'user',
      content: 'LangChain 和 CrewAI 哪个更适合做多 Agent 协作？',
      status: 'done',
      events: [],
    })
  }, 7000)

  setTimeout(() => {
    const msg2 = {
      id: 4,
      role: 'assistant',
      content: '',
      status: 'running',
      events: [],
      turn: 0,
      tokens: 0,
    }
    addMessage(msg2)

    setTimeout(() => {
      msg2.events.push({
        type: 'LLM_RESPONSE',
        turn: 2,
        thought: '用户问 LangChain 和 CrewAI 在多 Agent 协作场景的对比。基于已有信息直接回答即可。',
        toolNames: [],
        timestamp: Date.now(),
      })
    }, 500)

    setTimeout(() => {
      msg2.content = '对于**多 Agent 协作**场景，**CrewAI 更合适**。\n\nCrewAI 从设计之初就围绕"角色协作"构建，原生支持 Agent 角色、任务委派、顺序/并行执行。而 LangChain 虽然也能通过 LangGraph 实现多 Agent，但需要更多手动编排。\n\n如果项目核心是多 Agent 工作流，选 CrewAI；如果需要更广泛的 LLM 工具生态，选 LangChain。'
      msg2.status = 'done'
      msg2.tokens = 1820
    }, 1500)
  }, 7500)
})
</script>

<template>
  <div style="display:none">test mount</div>
</template>
