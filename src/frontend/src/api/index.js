/**
 * Eon Agent 后端 API 封装
 * 对应后端 cn.kong.eon.api.controller.*
 */

const BASE = '/api/v1'

async function request(path, options = {}) {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options,
  })

  if (!res.ok) {
    let msg = `HTTP ${res.status}`
    try {
      const err = await res.json()
      msg = err.message || err.error || msg
    } catch (_) { /* ignore */ }
    const e = new Error(msg)
    e.status = res.status
    throw e
  }

  if (res.status === 204) return null
  return res.json()
}

export const api = {
  /* ===== 健康检查 ===== */
  health: () => request('/health'),

  /* ===== 会话管理 ===== */
  createSession: (message = '') =>
    request('/sessions', {
      method: 'POST',
      body: JSON.stringify({ message }),
    }),

  listSessions: () => request('/sessions'),

  getSession: (sessionId) => request(`/sessions/${sessionId}`),

  closeSession: (sessionId) =>
    request(`/sessions/${sessionId}`, { method: 'DELETE' }),

  /* ===== 同步对话 ===== */
  chat: (sessionId, message) =>
    request('/chat', {
      method: 'POST',
      body: JSON.stringify({ sessionId, message }),
    }),

  /* ===== 异步对话 ===== */
  chatAsync: (sessionId, message) =>
    request('/chat/async', {
      method: 'POST',
      body: JSON.stringify({ sessionId, message }),
    }),

  getJobStatus: (jobId) => request(`/chat/jobs/${jobId}`),

  /* ===== 异步交互（AskQuestion） ===== */
  getInteraction: (sessionId) =>
    request(`/chat/${sessionId}/interaction`),

  submitAnswer: (sessionId, answers) =>
    request(`/chat/${sessionId}/answer`, {
      method: 'POST',
      body: JSON.stringify({ answers }),
    }),
}
