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
export async function interruptSession(sessionId, userId) {
  const res = await http.post('/interrupt', { sessionId }, { headers: userHeaders(userId) })
  return res?.status || 'no_session'
}

/**
 * 提交提问答案。答案直接交给阻塞中的 ask_question——本轮不中断，后端会把它作为工具结果继续跑。
 * 返回 'answered' | 'no_pending'（会话已不在等待回答）。
 */
export async function answerQuestion(sessionId, answers, userId) {
  const res = await http.post('/answer', { sessionId, answers }, { headers: userHeaders(userId) })
  return res?.status || 'no_pending'
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

/** 文件元信息：体积 / MIME / 是否二进制 / 编码。 */
export function getFileMeta(sessionId, path, userId) {
  return http.get(`/sessions/${encodeURIComponent(sessionId)}/files/meta`, {
    params: { path },
    headers: userHeaders(userId)
  })
}

/** 文件文本预览，超限时后端截断。 */
export function getFileContent(sessionId, path, userId) {
  return http.get(`/sessions/${encodeURIComponent(sessionId)}/files/content`, {
    params: { path },
    headers: userHeaders(userId)
  })
}

/** 原始文件链接，供 img / iframe / 下载直接引用（不经过 fetch）。 */
export function fileRawUrl(sessionId, path, download = false) {
  const q = `path=${encodeURIComponent(path)}${download ? '&download=1' : ''}`
  return `${API_BASE}/sessions/${encodeURIComponent(sessionId)}/files/raw?${q}`
}

