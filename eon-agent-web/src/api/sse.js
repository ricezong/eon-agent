/**
 * SSE 流式客户端。原生 EventSource 只支持 GET，而 /api/chat 是 POST + JSON body，
 * 故用 fetch + ReadableStream 手动解析帧，顺带获得中断与超时控制。
 */

const DEFAULT_TIMEOUT = 300000 // 与后端 SseEmitter(300_000L) 对齐

/** @returns {{ promise: Promise<void>, abort: (reason?: string) => void }} */
export function openSseStream({ url, body, headers, onEvent, onError, onDone, signal, timeout = DEFAULT_TIMEOUT } = {}) {
  const ctrl = new AbortController()
  const externalAbort = (e) => ctrl.abort(e?.target?.reason || new Error('aborted'))
  if (signal) signal.addEventListener('abort', externalAbort, { once: true })

  let settled = false
  let timer = null

  const abort = (reason = 'client_abort') => {
    if (settled) return
    settled = true
    clearTimeout(timer)
    ctrl.abort(typeof reason === 'string' ? new Error(reason) : reason)
  }

  const fail = (err) => {
    if (settled) return
    settled = true
    clearTimeout(timer)
    onError?.(normalizeError(err))
    onDone?.({ ok: false, error: normalizeError(err) })
  }

  const promise = (async () => {
    let res
    try {
      res = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          'Cache-Control': 'no-cache',
          ...headers
        },
        body: JSON.stringify(body ?? {}),
        signal: ctrl.signal
      })
    } catch (err) {
      return fail(err)
    }

    if (!res.ok || !res.body) {
      let detail = null
      try {
        detail = await res.json()
      } catch {
        detail = null
      }
      return fail({
        name: 'ApiError',
        message: detail?.message || `服务返回 HTTP ${res.status}`,
        type: detail?.type || `http_${res.status}`,
        status: res.status
      })
    }

    timer = setTimeout(() => abort('stream_timeout'), timeout)

    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    let received = 0

    try {
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        // 统一换行符，避免 \r\n / \r 混杂影响帧边界判定
        buffer += decoder.decode(value, { stream: true }).replace(/\r\n?/g, '\n')
        received += value?.byteLength || 0

        // 按空行切分完整帧
        let sepIndex
        while ((sepIndex = buffer.indexOf('\n\n')) !== -1) {
          const raw = buffer.slice(0, sepIndex)
          buffer = buffer.slice(sepIndex + 2)
          const frame = parseFrame(raw)
          if (!frame) continue
          try {
            const payload = frame.data ? JSON.parse(frame.data) : null
            // 事件名优先取 event: 行，缺失时回退 data 里的 type 字段
            const name = frame.event || payload?.type || 'message'
            onEvent?.({ name, data: payload, raw: frame.data })
          } catch (e) {
            onError?.({
              type: 'parse_error',
              message: 'SSE 数据帧解析失败',
              detail: frame.data,
              recoverable: true
            })
          }
        }
      }
      // 流正常结束
      if (settled) return
      settled = true
      clearTimeout(timer)
      onDone?.({ ok: true, received })
    } catch (err) {
      if (settled) return
      fail(err)
    }
  })()

  return { promise, abort }
}

/** 解析单帧为 { event, data, id }。 */
function parseFrame(raw) {
  if (!raw || !raw.trim()) return null
  const frame = { event: '', data: '', id: '' }
  const lines = raw.split('\n')
  const dataLines = []
  for (const line of lines) {
    if (!line || line.startsWith(':')) continue // 注释/心跳
    const idx = line.indexOf(':')
    const field = idx === -1 ? line : line.slice(0, idx)
    let value = idx === -1 ? '' : line.slice(idx + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    switch (field) {
      case 'event':
        frame.event = value
        break
      case 'data':
        dataLines.push(value)
        break
      case 'id':
        frame.id = value
        break
      default:
        break
    }
  }
  frame.data = dataLines.join('\n')
  return frame.event || frame.data ? frame : null
}

/** 归一化异常，附带给 UI 用的 type 与文案。 */
function normalizeError(err) {
  if (err && typeof err === 'object' && err.type) {
    return { type: err.type, message: err.message || '请求失败', status: err.status || 0, recoverable: true }
  }
  const name = err?.name || ''
  if (name === 'AbortError') {
    const reason = String(err?.message || '')
    if (reason.includes('stream_timeout')) {
      return { type: 'timeout', message: '等待响应超时（5 分钟），连接已关闭', recoverable: true }
    }
    return { type: 'aborted', message: '已停止生成', recoverable: true }
  }
  if (name === 'TypeError') {
    return { type: 'network', message: '无法连接后端服务，请确认 Spring Boot 已启动（默认 8080）', recoverable: true }
  }
  return { type: 'unknown', message: err?.message || '未知错误', recoverable: true }
}
