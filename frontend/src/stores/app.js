import { reactive, computed } from 'vue'
import { api } from '../api'

/**
 * 全局应用状态
 * - 会话列表
 * - 当前会话
 * - 消息流（每条消息含 turn 事件序列）
 */

export const store = reactive({
  // 会话列表
  sessions: [],
  sessionsLoading: false,

  // 当前会话
  currentSessionId: null,

  // 消息流：每个元素是 { id, role, content, events, status, turn, tokens, error }
  // role: 'user' | 'assistant'
  // status: 'pending' | 'running' | 'done' | 'terminated' | 'error'
  messages: [],

  // 每个会话的消息条数缓存（sessionId -> count）
  // 后端暂未返回消息条数，前端在发送/接收时维护
  sessionMessageCounts: {},

  // 全局运行状态
  running: false,

  // 错误提示
  toast: null,
})

export function showToast(message, type = 'info') {
  store.toast = { message, type, id: Date.now() }
  setTimeout(() => {
    if (store.toast && store.toast.id === store.toast.id) store.toast = null
  }, 4000)
}

export async function refreshSessions() {
  store.sessionsLoading = true
  try {
    const list = await api.listSessions()
    store.sessions = list || []
  } catch (e) {
    console.error('刷新会话列表失败', e)
  } finally {
    store.sessionsLoading = false
  }
}

export async function createNewSession(initialMessage = '') {
  const resp = await api.createSession(initialMessage)
  await refreshSessions()
  store.currentSessionId = resp.sessionId
  store.messages = []
  return resp.sessionId
}

export async function selectSession(sessionId) {
  store.currentSessionId = sessionId
  store.messages = []
  // 后端暂未提供历史消息查询接口，切换会话时清空前端消息流
  // 实际历史在 JSONL 中，需要后端补接口
}

export async function deleteSession(sessionId) {
  await api.closeSession(sessionId)
  if (store.currentSessionId === sessionId) {
    store.currentSessionId = null
    store.messages = []
  }
  await refreshSessions()
}

export function addMessage(msg) {
  store.messages.push(msg)
  // 维护当前会话的消息条数
  const sid = store.currentSessionId
  if (sid) {
    store.sessionMessageCounts[sid] = (store.sessionMessageCounts[sid] || 0) + 1
  }
  return msg
}

/** 获取指定会话的消息条数 */
export function getSessionMessageCount(sessionId) {
  if (!sessionId) return 0
  // 当前会话用实时 messages.length，其他会话用缓存
  if (sessionId === store.currentSessionId) {
    return store.messages.length
  }
  return store.sessionMessageCounts[sessionId] || 0
}

export function updateMessage(id, patch) {
  const msg = store.messages.find((m) => m.id === id)
  if (msg) Object.assign(msg, patch)
}

export function getLastAssistantMessage() {
  for (let i = store.messages.length - 1; i >= 0; i--) {
    if (store.messages[i].role === 'assistant') return store.messages[i]
  }
  return null
}

export const currentSession = computed(() =>
  store.sessions.find((s) => s.sessionId === store.currentSessionId)
)
