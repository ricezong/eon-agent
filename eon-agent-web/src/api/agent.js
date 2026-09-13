/** Agent 业务接口，与后端 AgentController 一一对应。用户标识统一走 X-User-Id 请求头。 */
import { http, API_BASE } from './http'
import { openSseStream } from './sse'

const USER_ID_HEADER = 'X-User-Id'

const userHeaders = (userId) => ({ [USER_ID_HEADER]: userId || 'default' })

/** 流式对话。返回 { promise, abort }；会话身份由首帧 session.start 交付。 */
export function chatStream({ sessionId, message, userId, kbId, modelId, retryMessageId } = {}, handlers = {}) {
  return openSseStream({
    url: `${API_BASE}/chat`,
    headers: userHeaders(userId),
    body: {
      sessionId: sessionId || null,
      message,
      kbId: kbId ?? null,
      modelId: modelId ?? null,
      retryMessageId: retryMessageId ?? null
    },
    ...handlers
  })
}

/** 中断指定会话的当前任务。返回 'interrupted' | 'no_session' */
export async function interruptSession(sessionId) {
  const res = await http.post('/interrupt', { sessionId })
  return res?.status || 'no_session'
}

export function listSessions(userId) {
  return http.get('/sessions', { headers: userHeaders(userId) })
}

export function deleteSession(sessionId, userId) {
  return http.del(`/sessions/${encodeURIComponent(sessionId)}`, { headers: userHeaders(userId) })
}

/** 返回与 SSE 数据帧结构一致的事件数组（首帧为 session.start）。 */
export function getSessionEvents(sessionId, userId) {
  return http.get(`/sessions/${encodeURIComponent(sessionId)}`, { headers: userHeaders(userId) })
}

/** 连通性探测（复用会话列表接口）。 */
export async function ping(userId) {
  const started = performance.now()
  await listSessions(userId)
  return Math.round(performance.now() - started)
}
