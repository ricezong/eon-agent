import { reactive, computed, markRaw } from 'vue'
import { api } from '../api'

/**
 * 对话状态管理（参考 frontend_old useChat.ts 模式）
 *
 * 核心设计：
 * 1. 每会话独立运行时（messages/sending/conn），切换会话不丢失消息
 * 2. 懒创建会话：newSession() 只清除 currentSessionId，
 *    实际会话由 SSE 流创建（后端 GET /stream 无 sessionId 时自动创建）
 * 3. SSE 连接引用存储在 runtime 中，支持 stop/abort
 * 4. 后端为唯一事实源，会话列表从后端拉取
 */

/* ==================== 运行时状态 ==================== */

// 每会话运行时 Map：sessionId -> SessionRuntime
const _runtimes = reactive(new Map())

// 待定运行时键名（currentSessionId 为 null 时使用）
const PENDING_KEY = '__pending__'

function getRuntime(sessionId) {
  const key = sessionId || PENDING_KEY
  let rt = _runtimes.get(key)
  if (!rt) {
    rt = reactive({
      messages: [],
      sending: false,
      conn: null,
    })
    _runtimes.set(key, rt)
  }
  return rt
}

function removeRuntime(sessionId) {
  const key = sessionId || PENDING_KEY
  const rt = _runtimes.get(key)
  if (rt?.conn) {
    try { rt.conn.close() } catch (_) { /* ignore */ }
  }
  _runtimes.delete(key)
}

/* ==================== 全局 Store ==================== */

export const store = reactive({
  // 会话列表（从后端拉取）
  sessions: [],
  sessionsLoading: false,

  // 当前会话 ID（null 表示未选中/新建会话）
  currentSessionId: null,

  // 错误提示
  toast: null,

  // 每个会话的消息条数缓存（sessionId -> count）
  // 仅用于未在前端打开过的会话
  sessionMessageCounts: {},

  // --- 以下为 getter，基于 per-session runtime 动态计算 ---

  // 当前会话的消息列表
  get messages() {
    const rt = getRuntime(this.currentSessionId)
    return rt ? rt.messages : []
  },

  // 当前会话是否正在发送
  get sending() {
    const rt = getRuntime(this.currentSessionId)
    return rt ? rt.sending : false
  },

  // 全局运行状态：任一会话正在发送则为 true
  get running() {
    for (const rt of _runtimes.values()) {
      if (rt.sending) return true
    }
    return false
  },
})

/* ==================== Toast ==================== */

export function showToast(message, type = 'info') {
  store.toast = { message, type, id: Date.now() }
  setTimeout(() => {
    store.toast = null
  }, 4000)
}

/* ==================== 会话管理 ==================== */

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

/**
 * 新建会话（懒创建）
 * 只清除 currentSessionId，不调用后端 API。
 * 实际会话在用户发送第一条消息时由 SSE 流自动创建。
 */
export function newSession() {
  store.currentSessionId = null
}

/**
 * 选择会话
 * 消息保留在 per-session runtime 中，切换时自动恢复。
 * 后端暂未提供历史消息查询接口，未访问过的会话消息列表为空。
 */
export async function selectSession(sessionId) {
  store.currentSessionId = sessionId
}

/**
 * 删除会话
 * 先中断 SSE 连接，再删除 runtime 和后端会话。
 */
export async function deleteSession(sessionId) {
  removeRuntime(sessionId)
  delete store.sessionMessageCounts[sessionId]
  if (store.currentSessionId === sessionId) {
    store.currentSessionId = null
  }
  try {
    await api.closeSession(sessionId)
  } catch (e) {
    console.error('关闭会话失败', e)
  }
  await refreshSessions()
}

/**
 * 从 RUN_START 事件捕获 sessionId（懒创建回填）
 * 将 pending runtime 迁移到真实 sessionId。
 */
export function captureSessionId(sessionId) {
  if (!sessionId) return
  if (store.currentSessionId === sessionId) return

  // 迁移 pending runtime 到真实 sessionId
  const pendingRt = _runtimes.get(PENDING_KEY)
  if (pendingRt) {
    _runtimes.delete(PENDING_KEY)
    _runtimes.set(sessionId, pendingRt)
  }

  store.currentSessionId = sessionId

  // 刷新会话列表（新会话由后端创建）
  refreshSessions().catch(() => {})
}

/* ==================== SSE 连接管理 ==================== */

/**
 * 存储 SSE 连接引用到当前会话 runtime
 */
export function setConn(conn) {
  const rt = getRuntime(store.currentSessionId)
  if (rt) rt.conn = markRaw(conn)
}

/**
 * 中断当前会话的 SSE 流
 */
export function stopStream() {
  const rt = getRuntime(store.currentSessionId)
  if (rt?.conn) {
    try { rt.conn.close() } catch (_) { /* ignore */ }
    rt.conn = null
    rt.sending = false
  }
}

/**
 * 设置当前会话的发送状态
 */
export function setSending(val) {
  const rt = getRuntime(store.currentSessionId)
  if (rt) rt.sending = val
}

/* ==================== 消息操作 ==================== */

export function addMessage(msg) {
  const rt = getRuntime(store.currentSessionId)
  rt.messages.push(msg)
  return msg
}

export function updateMessage(id, patch) {
  const rt = getRuntime(store.currentSessionId)
  const msg = rt.messages.find((m) => m.id === id)
  if (msg) Object.assign(msg, patch)
}

export function getLastAssistantMessage() {
  const rt = getRuntime(store.currentSessionId)
  for (let i = rt.messages.length - 1; i >= 0; i--) {
    if (rt.messages[i].role === 'assistant') return rt.messages[i]
  }
  return null
}

/**
 * 获取指定会话的消息条数
 * 优先使用 runtime 中的实际条数，其次用缓存/后端 turnCount。
 */
export function getSessionMessageCount(sessionId) {
  if (!sessionId) return 0
  const rt = _runtimes.get(sessionId)
  if (rt) return rt.messages.length
  if (store.sessionMessageCounts[sessionId]) return store.sessionMessageCounts[sessionId]
  const session = store.sessions.find((s) => s.sessionId === sessionId)
  return session?.turnCount || 0
}

/* ==================== Computed ==================== */

export const currentSession = computed(() =>
  store.sessions.find((s) => s.sessionId === store.currentSessionId)
)
