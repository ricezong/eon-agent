/**
 * SSE 客户端 - 全量支持后端 AgentEvent 事件
 *
 * 后端事件类型（AgentEvent.EventType）：
 *   RUN_START      - Agent 开始运行
 *   TURN_START     - Turn 开始
 *   LLM_RESPONSE   - LLM 响应（思考文本 thought + 工具调用名 toolNames）
 *   TOOL_START     - 工具开始执行（toolName）
 *   TOOL_RESULT    - 工具执行完成（toolName, success, summary）
 *   TURN_END       - Turn 结束（totalTokens）
 *   DONE           - 正常完成（output, turnCount, totalTokens）
 *   TERMINATED     - 被强制终止（error, turnCount, totalTokens）
 *   ERROR          - 出错（error）
 *
 * 后端 SSE 格式：
 *   event: <EventType>
 *   data: <AgentEvent JSON>
 */

export function createSseStream({ sessionId, message, onEvent, onError, onClose }) {
  // 后端 GET /api/v1/stream?sessionId=&message=
  const params = new URLSearchParams({ message })
  if (sessionId) params.set('sessionId', sessionId)
  const url = `/api/v1/stream?${params.toString()}`

  // 使用 fetch + ReadableStream 解析 SSE（比 EventSource 更灵活，支持自定义 header 和错误处理）
  const controller = new AbortController()
  let closed = false

  ;(async () => {
    try {
      const res = await fetch(url, {
        method: 'GET',
        headers: { Accept: 'text/event-stream' },
        signal: controller.signal,
      })

      if (!res.ok) {
        throw new Error(`SSE 连接失败: HTTP ${res.status}`)
      }

      const reader = res.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        // SSE 事件以双换行分隔
        const blocks = buffer.split('\n\n')
        buffer = blocks.pop() || ''

        for (const block of blocks) {
          const parsed = parseSseBlock(block)
          if (parsed) {
            onEvent?.(parsed.event, parsed.data)
          }
        }
      }

      // 处理最后残留
      if (buffer.trim()) {
        const parsed = parseSseBlock(buffer)
        if (parsed) onEvent?.(parsed.event, parsed.data)
      }

      if (!closed) {
        closed = true
        onClose?.()
      }
    } catch (err) {
      if (err.name === 'AbortError') {
        if (!closed) {
          closed = true
          onClose?.()
        }
        return
      }
      onError?.(err)
    }
  })()

  return {
    close: () => {
      if (!closed) {
        closed = true
        controller.abort()
      }
    },
  }
}

function parseSseBlock(block) {
  const lines = block.split('\n')
  let event = 'message'
  const dataLines = []

  for (const line of lines) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trim())
    }
  }

  if (dataLines.length === 0) return null

  const dataStr = dataLines.join('\n')
  let data
  try {
    data = JSON.parse(dataStr)
  } catch (_) {
    data = { raw: dataStr }
  }

  return { event, data }
}
