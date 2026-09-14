/**
 * HTTP 基础层。
 * 统一处理：baseURL、JSON 解析、后端统一错误结构（{status,type,message}）与超时。
 */

export class ApiError extends Error {
  constructor(message, { type = 'unknown', status = 0, payload = null } = {}) {
    super(message || '请求失败')
    this.name = 'ApiError'
    this.type = type
    this.status = status
    this.payload = payload
  }
}

export const API_BASE = '/api'

function buildUrl(path, params) {
  const url = `${API_BASE}${path}`
  if (!params) return url
  const qs = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join('&')
  return qs ? `${url}?${qs}` : url
}

async function parseBody(res) {
  const text = await res.text()
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

/**
 * 通用 JSON 请求。
 * @param {string} path 以 / 开头的接口路径，如 /sessions
 */
export async function request(path, { method = 'GET', params, body, headers, signal, timeout = 20000 } = {}) {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(new Error('timeout')), timeout)
  const onAbort = () => ctrl.abort()
  if (signal) signal.addEventListener('abort', onAbort, { once: true })

  try {
    const res = await fetch(buildUrl(path, params), {
      method,
      headers: {
        Accept: 'application/json',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...headers
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: ctrl.signal
    })

    const data = await parseBody(res)

    if (!res.ok) {
      // 后端 GlobalExceptionHandler: { status:'error', type, message }
      const payload = data && typeof data === 'object' ? data : {}
      throw new ApiError(payload.message || `请求失败（HTTP ${res.status}）`, {
        type: payload.type || `http_${res.status}`,
        status: res.status,
        payload
      })
    }
    return data
  } catch (err) {
    if (err instanceof ApiError) throw err
    if (err?.name === 'AbortError') {
      if (signal?.aborted) throw new ApiError('请求已取消', { type: 'aborted' })
      throw new ApiError('请求超时，请检查后端服务是否可用', { type: 'timeout' })
    }
    throw new ApiError(err?.message || '网络异常，无法连接后端服务', { type: 'network' })
  } finally {
    clearTimeout(timer)
    if (signal) signal.removeEventListener('abort', onAbort)
  }
}

export const http = {
  get: (path, opts) => request(path, { ...opts, method: 'GET' }),
  post: (path, body, opts) => request(path, { ...opts, method: 'POST', body }),
  del: (path, opts) => request(path, { ...opts, method: 'DELETE' })
}
