<script setup>
import { ref, computed, nextTick } from 'vue'
import { store, addMessage, updateMessage, showToast } from '../stores/app'
import { createSseStream } from '../api/sse'
import { api } from '../api'
import Icon from './Icon.vue'

const input = ref('')
const sending = ref(false)
const textareaRef = ref(null)
const focused = ref(false)

const canSend = computed(() => input.value.trim() && !sending.value)

async function autoResize() {
  await nextTick()
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 180) + 'px'
}

async function send() {
  if (!canSend.value) return
  const text = input.value.trim()
  input.value = ''
  sending.value = true
  await autoResize()

  let sessionId = store.currentSessionId
  if (!sessionId) {
    try {
      const resp = await api.createSession(text)
      sessionId = resp.sessionId
      store.currentSessionId = sessionId
    } catch (e) {
      showToast('创建会话失败：' + e.message, 'error')
      sending.value = false
      return
    }
  }

  addMessage({
    id: Date.now(),
    role: 'user',
    content: text,
    status: 'done',
    events: [],
  })

  const assistantMsg = {
    id: Date.now() + 1,
    role: 'assistant',
    content: '',
    status: 'running',
    events: [],
    turn: 0,
    tokens: 0,
  }
  addMessage(assistantMsg)

  startStream(sessionId, text, assistantMsg)
}

function startStream(sessionId, message, assistantMsg) {
  let currentTurn = 0

  createSseStream({
    sessionId,
    message,
    onEvent: (eventType, data) => {
      handleEvent(eventType, data, assistantMsg, (t) => { currentTurn = t })
    },
    onError: (err) => {
      updateMessage(assistantMsg.id, {
        status: 'error',
        error: err.message || '连接失败',
      })
      sending.value = false
      showToast('SSE 连接失败：' + err.message, 'error')
    },
    onClose: () => {
      if (assistantMsg.status === 'running') {
        updateMessage(assistantMsg.id, { status: 'done' })
      }
      sending.value = false
    },
  })
}

function handleEvent(eventType, data, assistantMsg, setTurn) {
  switch (eventType) {
    case 'RUN_START':
      store.running = true
      break

    case 'TURN_START':
      if (data.turn) {
        setTurn(data.turn)
        updateMessage(assistantMsg.id, { turn: data.turn })
      }
      break

    case 'LLM_RESPONSE':
      assistantMsg.events.push({
        type: 'LLM_RESPONSE',
        turn: data.turn,
        thought: data.thought,
        toolNames: data.toolNames,
      })
      break

    case 'TOOL_START':
      assistantMsg.events.push({
        type: 'TOOL_START',
        toolName: data.toolName,
        turn: data.turn,
      })
      break

    case 'TOOL_RESULT':
      assistantMsg.events.push({
        type: 'TOOL_RESULT',
        toolName: data.toolName,
        success: data.success,
        summary: data.summary,
        turn: data.turn,
      })
      break

    case 'TURN_END':
      if (data.totalTokens) {
        updateMessage(assistantMsg.id, { tokens: data.totalTokens })
      }
      break

    case 'DONE':
      updateMessage(assistantMsg.id, {
        content: data.output || '',
        status: 'done',
        tokens: data.totalTokens || assistantMsg.tokens,
        turn: data.turnCount || assistantMsg.turn,
      })
      sending.value = false
      store.running = false
      break

    case 'TERMINATED':
      updateMessage(assistantMsg.id, {
        status: 'terminated',
        error: data.error || '任务被强制终止',
        tokens: data.totalTokens || assistantMsg.tokens,
      })
      sending.value = false
      store.running = false
      break

    case 'ERROR':
      updateMessage(assistantMsg.id, {
        status: 'error',
        error: data.error || '执行出错',
      })
      sending.value = false
      store.running = false
      break
  }
}

function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}
</script>

<template>
  <div class="input-area">
    <div :class="['input-wrapper', { focused, disabled: sending }]">
      <textarea
        ref="textareaRef"
        v-model="input"
        class="input"
        placeholder="输入消息，Enter 发送，Shift+Enter 换行…"
        rows="1"
        :disabled="sending"
        @input="autoResize"
        @keydown="onKeydown"
        @focus="focused = true"
        @blur="focused = false"
      ></textarea>

      <div class="input-actions">
        <div class="session-info" v-if="store.currentSessionId">
          <Icon name="message-square" :size="12" />
          <span>{{ store.currentSessionId.slice(0, 8) }}…</span>
        </div>

        <button
          class="send-btn"
          :disabled="!canSend"
          @click="send"
          :title="canSend ? '发送 (Enter)' : '输入消息后发送'"
        >
          <Icon v-if="sending" name="loader" :size="18" class="spin" />
          <Icon v-else name="arrow-up" :size="18" />
        </button>
      </div>
    </div>

    <div class="input-footer">
      <div class="footer-left">
        <span class="hint-item">
          <Icon name="info" :size="11" />
          AI 由 Eon Agent 驱动
        </span>
      </div>
      <div class="footer-right">
        <span class="hint-item" v-if="sending">
          <Icon name="loader" :size="11" class="spin" />
          正在思考…
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.input-area {
  padding: 12px 24px 16px;
  background: linear-gradient(to top, var(--bg-base) 60%, transparent);
}

.input-wrapper {
  position: relative;
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 8px 8px 8px 16px;
  background: var(--bg-glass-strong);
  backdrop-filter: blur(20px);
  border: 1px solid var(--border-default);
  border-radius: 20px;
  transition: all var(--dur-fast);
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.3);
}

.input-wrapper.focused {
  border-color: var(--border-primary);
  box-shadow: 0 0 0 4px rgba(99, 102, 241, 0.12), 0 4px 24px rgba(0, 0, 0, 0.3);
}

.input-wrapper.disabled {
  opacity: 0.7;
}

.input {
  flex: 1;
  background: transparent;
  border: none;
  outline: none;
  color: var(--t-primary);
  font-size: 14px;
  line-height: 1.6;
  resize: none;
  font-family: inherit;
  max-height: 180px;
  padding: 6px 0;
}
.input::placeholder { color: var(--t-tertiary); }

.input-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  padding-bottom: 2px;
}

.session-info {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--t-tertiary);
  font-family: 'JetBrains Mono', monospace;
  background: var(--bg-surface);
  padding: 3px 8px;
  border-radius: 10px;
  border: 1px solid var(--border-subtle);
}

.send-btn {
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 12px;
  background: var(--grad-primary);
  color: white;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all var(--dur-fast);
  flex-shrink: 0;
}
.send-btn:hover:not(:disabled) {
  transform: translateY(-1px) scale(1.05);
  box-shadow: 0 6px 20px rgba(99, 102, 241, 0.5);
}
.send-btn:active:not(:disabled) {
  transform: translateY(0) scale(0.98);
}
.send-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
  background: var(--bg-surface-hover);
  color: var(--t-tertiary);
}

.spin {
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

.input-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
  padding: 0 4px;
}
.footer-left, .footer-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.hint-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--t-tertiary);
}
</style>
