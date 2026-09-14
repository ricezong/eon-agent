/**
 * 会话与消息状态中心。一条 assistant 消息 = blocks[]（thinking / text / tool / file / web），保证时序交错。
 * 实时流与历史回放共用同一套事件 → 消息还原逻辑；会话身份取自每个事件携带的 session_id。
 */
import { defineStore } from 'pinia'
import { computed, ref, shallowRef } from 'vue'
import * as api from '@/api/agent'
import { attach, detach, flush } from '@/utils/ticker'
import { toolMeta } from '@/config/tools'
import { useSettingsStore } from './settings'
import { useToastStore } from './toast'
import { deriveTitle, safeJson, uid } from '@/utils/format'

/* ── 块构造 ─────────────────────────────── */
const makeTextBlock = (text = '') => ({ id: uid('b'), kind: 'text', target: text, shown: '', closed: false })
const makeThinkingBlock = (text = '') => ({ id: uid('b'), kind: 'thinking', text, closed: false })
const makeToolBlock = (toolUseId, name, input) => ({
  id: uid('b'),
  kind: 'tool',
  toolUseId,
  name,
  input,
  args: safeJson(input, null),
  state: 'running', // running | success | error
  content: '',
  structured: null,
  success: true,
  opened: false
})

/**
 * 声明了预览能力的工具（write / download_file / web_fetch）在消息流中单独成卡，
 * 不把结果塞进折叠的工具卡里。块状态随调用推进：generating → writing → done | error。
 * - generating：模型还在流式产出工具入参（写文件时就是文件内容），此时页面必须有反馈
 * - writing：入参已就绪，工具正在落盘或抓取
 */
const RESULT_BLOCK_FIELD = {
  file: () => ({ path: '', fileSize: '', sizeBytes: 0 }),
  web: () => ({ pages: [] })
}

/** 工具名 → 结果卡块类型；未声明 preview 的工具不单独成卡。 */
function resultKindOf(toolName) {
  const p = toolMeta(toolName).preview
  return p === 'file' || p === 'web' ? p : ''
}

const makeResultBlock = (kind, key, name) => ({
  id: uid('b'),
  kind,
  key,
  toolUseId: '',
  name,
  state: 'generating',
  argsChars: 0,
  content: '',
  ...RESULT_BLOCK_FIELD[kind]()
})

const isResultBlock = (b) => b && (b.kind === 'file' || b.kind === 'web')

/** 按 key（tool_use_id 或流序号）找未认领的结果块，用于把 delta 与后续的 tool_use 对上。 */
function findResultBlock(blocks, key, kind) {
  for (let i = blocks.length - 1; i >= 0; i--) {
    const b = blocks[i]
    if (!isResultBlock(b)) continue
    if (kind && b.kind !== kind) continue
    if (key && b.key === key) return b
    if (!key && !b.key) return b
  }
  return null
}

export const useSessionStore = defineStore('session', () => {
  const settings = useSettingsStore()
  const toast = useToastStore()

  /* ── 状态 ─────────────────────────────── */
  const sessions = ref([])
  const sessionsLoading = ref(false)
  const currentId = ref('')
  const messages = ref([])
  const streaming = ref(false)
  const sessionStatus = ref('idle') // idle | running | terminated
  const loadingSession = ref(false)
  const lastError = ref(null)
  const connection = ref('unknown') // unknown | online | offline
  const elapsed = ref(0)
  /** 当前正在执行的引擎钩子名（空串表示不在钩子阶段），用于静默期的进度提示 */
  const hookPhase = ref('')

  const run = shallowRef(null) // { abort }
  let currentAssistant = null // 当前正在生成的 assistant 消息（非响应式引用）
  let errored = false
  let timer = null

  /* ── 计算属性 ─────────────────────────── */
  const currentSession = computed(() => sessions.value.find((s) => s.sessionId === currentId.value) || null)
  const title = computed(() => currentSession.value?.title || (messages.value.length ? '新会话' : 'Eon Agent'))
  const hasMessages = computed(() => messages.value.length > 0)
  const lastUsage = computed(() => {
    for (let i = messages.value.length - 1; i >= 0; i--) {
      if (messages.value[i].usage) return messages.value[i].usage
    }
    return null
  })
  const toolCount = computed(() =>
    messages.value.reduce(
      (n, m) => n + (m.blocks?.filter((b) => b.kind === 'tool').length || 0),
      0
    )
  )

  /* ── 会话列表 ─────────────────────────── */
  async function loadSessions() {
    sessionsLoading.value = true
    try {
      sessions.value = await api.listSessions(settings.userId)
      connection.value = 'online'
    } catch (e) {
      connection.value = 'offline'
      throw e
    } finally {
      sessionsLoading.value = false
    }
  }

  /** 采纳事件流携带的会话身份。仅在尚无当前会话时生效。 */
  function adoptSession(id, sessionTitle) {
    if (!id || currentId.value) return
    currentId.value = id
    const placeholder = {
      sessionId: id,
      title: sessionTitle || deriveTitle(lastUserText()),
      userMessageCount: 1,
      lastActivityAt: new Date().toISOString()
    }
    sessions.value = [placeholder, ...sessions.value.filter((s) => s.sessionId !== id)]
  }

  function newSession() {
    if (streaming.value) stop()
    currentId.value = ''
    messages.value = []
    lastError.value = null
    sessionStatus.value = 'idle'
    elapsed.value = 0
  }

  async function openSession(id, force = false) {
    if (!id) return
    if (!force && id === currentId.value) return
    if (streaming.value) stop()
    loadingSession.value = true
    lastError.value = null
    try {
      const events = await api.getSessionEvents(id, settings.userId)
      messages.value = buildMessages(events)
      currentId.value = id
      sessionStatus.value = 'idle'
      connection.value = 'online'
    } catch (e) {
      connection.value = 'offline'
      toast.error(`加载会话失败：${e.message}`)
      throw e
    } finally {
      loadingSession.value = false
    }
  }

  async function removeSession(id) {
    try {
      await api.deleteSession(id, settings.userId)
      sessions.value = sessions.value.filter((s) => s.sessionId !== id)
      if (currentId.value === id) newSession()
      toast.success('会话已删除')
    } catch (e) {
      toast.error(`删除失败：${e.message}`)
    }
  }

  /* ── 发送消息（SSE 流式） ─────────────── */
  async function send(text) {
    const content = (text || '').trim()
    if (!content || streaming.value) return

    lastError.value = null
    errored = false

    messages.value.push({ id: uid('u'), role: 'user', text: content, createdAt: Date.now() })
    const assistant = {
      id: uid('a'),
      role: 'assistant',
      blocks: [],
      usage: null,
      status: 'streaming',
      stopReason: null,
      error: null,
      createdAt: Date.now()
    }
    messages.value.push(assistant)
    currentAssistant = messages.value[messages.value.length - 1]

    streaming.value = true
    sessionStatus.value = 'running'
    elapsed.value = 0
    timer = setInterval(() => {
      elapsed.value += 1
    }, 1000)

    const stream = api.chatStream(
      { sessionId: currentId.value || null, message: content, userId: settings.userId },
      {
        onEvent: applyEvent,
        onError: (err) => {
          lastError.value = err
          if (err.type !== 'aborted') {
            toast.error(err.message || '连接异常')
            if (currentAssistant) currentAssistant.error = { message: err.message, type: err.type }
          }
          finishRun(err.type === 'aborted' ? 'stopped' : 'error', err.message)
        },
        onDone: ({ ok }) => {
          if (ok && sessionStatus.value === 'running') finishRun(errored ? 'error' : 'done')
        }
      }
    )
    run.value = stream

    stream.promise.catch(() => {})
  }

  /** 停止生成：调用后端 /api/interrupt 并断开本地流。 */
  async function stop() {
    if (!streaming.value) return
    if (currentId.value) {
      try {
        await api.interruptSession(currentId.value)
      } catch {
        /* 忽略：即便后端无会话，本地也要断开 */
      }
    }
    run.value?.abort('user_stop')
    run.value = null
  }

  /* ── 事件 → 消息 ──────────────────────── */
  function currentMsg(create = true) {
    const last = messages.value[messages.value.length - 1]
    if (last && last.role === 'assistant') return last
    if (!create) return null
    const m = {
      id: uid('a'),
      role: 'assistant',
      blocks: [],
      usage: null,
      status: 'streaming',
      stopReason: null,
      error: null,
      createdAt: Date.now()
    }
    messages.value.push(m)
    return messages.value[messages.value.length - 1]
  }

  /** 推入块并返回响应式引用；同时关闭上一个块 */
  function pushBlock(msg, block, attachTyping = false) {
    const prev = msg.blocks[msg.blocks.length - 1]
    if (prev) prev.closed = true
    msg.blocks.push(block)
    const ref = msg.blocks[msg.blocks.length - 1]
    if (attachTyping) attach(ref)
    return ref
  }

  function appendText(msg, delta) {
    if (!delta) return
    const last = msg.blocks[msg.blocks.length - 1]
    if (last && last.kind === 'text' && !last.closed) {
      last.target += delta
      attach(last)
    } else {
      pushBlock(msg, makeTextBlock(delta), true)
    }
  }

  function appendThinking(msg, delta) {
    if (!delta) return
    const last = msg.blocks[msg.blocks.length - 1]
    if (last && last.kind === 'thinking' && !last.closed) {
      last.text += delta
    } else {
      pushBlock(msg, makeThinkingBlock(delta))
    }
  }

  function setFinalText(msg, text) {
    if (!text) return
    const last = msg.blocks[msg.blocks.length - 1]
    if (last && last.kind === 'text' && !last.closed) {
      last.target = text
      attach(last)
    } else {
      pushBlock(msg, makeTextBlock(text), true)
    }
  }

  function pushTool(msg, data) {
    const block = pushBlock(msg, makeToolBlock(data.tool_use_id, data.name, data.input))
    return block
  }

  /** 把已存在的块挪到消息末尾，用于让结果卡排在随后出现的工具卡之后。 */
  function moveToEnd(msg, block) {
    const i = msg.blocks.indexOf(block)
    if (i === -1 || i === msg.blocks.length - 1) return
    msg.blocks.splice(i, 1)
    msg.blocks.push(block)
  }

  /**
   * 工具入参流式产出。首个 delta 带工具名，据此建卡；后续 delta 只带片段，按 key 累加。
   * 这是写文件「长时间无反馈」的主要窗口——内容生成阶段最久，必须在这里就有进度。
   */
  function applyToolDelta(msg, data) {
    const key = data.tool_use_id || `#${data.index ?? 0}`
    let block = findResultBlock(msg.blocks, key, null)
    if (!block) {
      const kind = resultKindOf(data.name)
      if (!kind) return
      block = pushBlock(msg, makeResultBlock(kind, key, data.name || ''))
    }
    block.argsChars += (data.delta || '').length
  }

  /** 入参就绪、工具开始执行：认领 generating 中的结果卡并切到 writing。 */
  function applyToolUse(msg, data) {
    pushTool(msg, data)
    const kind = resultKindOf(data.name)
    if (!kind) return

    let rb = findResultBlock(msg.blocks, data.tool_use_id, kind)
    if (!rb) {
      // delta 阶段拿不到 tool_use_id，认领最后一块未归属的同类型结果卡
      const pending = [...msg.blocks].reverse().find((b) => isResultBlock(b) && b.kind === kind && !b.toolUseId)
      if (pending) {
        pending.toolUseId = data.tool_use_id
        pending.key = data.tool_use_id
        rb = pending
      }
    }
    if (!rb) rb = pushBlock(msg, makeResultBlock(kind, data.tool_use_id, data.name))

    rb.state = 'writing'
    rb.name = data.name || rb.name

    // 入参里的路径 / 链接先填上，执行阶段就能显示文件名或链接数
    const args = safeJson(data.input, null)
    if (args && typeof args === 'object') {
      const p = args.file_path || args.target_file || args.file_name
      if (p && !rb.path) rb.path = String(p)
      if (kind === 'web' && Array.isArray(args.urls) && !(rb.pages || []).length) {
        rb.pages = args.urls.map((u) => ({ url: String(u), success: true, contentLength: 0 }))
      }
    }
    moveToEnd(msg, rb)
  }

  /** 工具执行完毕：把结构化结果写进结果卡，仍保留精简的工具卡。 */
  function applyToolResult(msg, data) {
    fillTool(msg, data)
    const kind = resultKindOf(data.name)
    if (!kind) return

    let rb = findResultBlock(msg.blocks, data.tool_use_id, kind)
    if (!rb) {
      // 成功但没有可用载荷时不建卡，避免出现空白的结果卡
      const payload = kind === 'file' ? !!data.structured_content?.filePath : (data.structured_content?.pages || []).length > 0
      if (data.success !== false && !payload) return
      rb = pushBlock(msg, makeResultBlock(kind, data.tool_use_id, data.name))
    }

    rb.state = data.success === false ? 'error' : 'done'
    rb.content = data.content || ''
    const view = data.structured_content || null
    if (kind === 'file') {
      rb.path = view?.filePath || rb.path
      rb.fileSize = view?.fileSize || ''
      rb.sizeBytes = Number(view?.sizeBytes) || 0
    } else {
      rb.pages = view?.pages || rb.pages || []
    }
  }

  function fillTool(msg, data) {
    // 工具结果可能落在上一条 assistant 消息（跨轮），向前回溯 3 条
    const candidates = [msg, ...messages.value.slice(-4, -1).reverse()]
    for (const m of candidates) {
      if (!m || m.role !== 'assistant') continue
      const hit = m.blocks.find((b) => b.kind === 'tool' && b.toolUseId === data.tool_use_id)
      if (hit) {
        hit.state = data.success === false ? 'error' : 'success'
        hit.success = data.success !== false
        hit.content = data.content || ''
        hit.structured = data.structured_content || null
        return
      }
    }
    // 未匹配到 tool_use（如回放缺少上下文）时补一块
    const block = pushBlock(msg, makeToolBlock(data.tool_use_id, data.name, '{}'))
    block.state = data.success === false ? 'error' : 'success'
    block.content = data.content || ''
    block.structured = data.structured_content || null
  }

  function applyEvent({ name, data }) {
    if (!data) return
    // 每个事件都带 session_id，故此处统一采纳身份，无需为 session.start 单独分支
    adoptSession(data.session_id, data.title)
    // 钩子阶段只是「当前状态」，收到任何其他事件即视为该阶段已结束
    hookPhase.value = name === 'engine.hook' ? data.hook || '' : ''
    switch (name) {
      case 'session.status': {
        if (data.status === 'running') {
          streaming.value = true
          sessionStatus.value = 'running'
        } else if (data.status === 'terminated') {
          sessionStatus.value = 'terminated'
          finishRun('terminated', data.stop_reason)
        } else if (data.status === 'idle') {
          finishRun(errored ? 'error' : 'done', data.stop_reason)
        }
        break
      }
      case 'engine.delta': {
        const msg = currentMsg()
        if (data.kind === 'thinking') appendThinking(msg, data.delta)
        else appendText(msg, data.delta)
        break
      }
      case 'engine.thinking': {
        const msg = currentMsg()
        appendThinking(msg, data.content)
        break
      }
      case 'engine.message': {
        const msg = currentMsg()
        const text = joinParts(data.content)
        setFinalText(msg, text)
        break
      }
      case 'engine.tool_delta': {
        const msg = currentMsg()
        applyToolDelta(msg, data)
        break
      }
      case 'engine.tool_use': {
        const msg = currentMsg()
        applyToolUse(msg, data)
        break
      }
      case 'engine.tool_result': {
        const msg = currentMsg()
        applyToolResult(msg, data)
        break
      }
      case 'session.usage': {
        const msg = currentMsg()
        msg.usage = {
          prompt: data.prompt_tokens || 0,
          completion: data.completion_tokens || 0,
          total: data.total_tokens || 0
        }
        break
      }
      case 'session.error': {
        errored = true
        const msg = currentMsg()
        msg.error = { message: data.message, type: data.error_type }
        break
      }
      default:
        break
    }
  }

  /* ── 收尾 ─────────────────────────────── */
  function finishRun(status, stopReason = null) {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    const msg = currentAssistant || currentMsg(false)
    if (msg) {
      for (const b of msg.blocks) {
        if (b.kind === 'text') {
          flush(b)
          detach(b)
        }
      }
      if (msg.status === 'streaming') {
        msg.status = status
        msg.stopReason = stopReason
      }
    }
    currentAssistant = null
    streaming.value = false
    sessionStatus.value = status === 'terminated' ? 'terminated' : 'idle'
    run.value = null
    hookPhase.value = ''

    // 收尾后刷新列表（标题 / 消息数 / 活跃时间）
    refreshAfterRun()
  }

  async function refreshAfterRun() {
    try {
      sessions.value = await api.listSessions(settings.userId)
      connection.value = 'online'
    } catch {
      connection.value = 'offline'
    }
  }

  function lastUserText() {
    for (let i = messages.value.length - 1; i >= 0; i--) {
      if (messages.value[i].role === 'user') return messages.value[i].text
    }
    return ''
  }

  return {
    sessions,
    sessionsLoading,
    currentId,
    messages,
    streaming,
    sessionStatus,
    loadingSession,
    lastError,
    connection,
    elapsed,
    hookPhase,
    currentSession,
    title,
    hasMessages,
    lastUsage,
    toolCount,
    loadSessions,
    newSession,
    openSession,
    removeSession,
    send,
    stop,
    applyEvent
  }
})

/* ── 回放：事件数组 → 消息列表 ──────────── */

/** 回放没有中间态，结果卡在 tool_result 处一次性成型，接在工具卡之后。 */
function pushReplayResult(msg, ev) {
  const kind = resultKindOf(ev.name)
  if (!kind) return
  const view = ev.structured_content || null
  const ok = ev.success !== false
  const rb = makeResultBlock(kind, ev.tool_use_id, ev.name)
  rb.state = ok ? 'done' : 'error'
  rb.content = ev.content || ''
  if (kind === 'file') {
    rb.path = view?.filePath || ''
    rb.fileSize = view?.fileSize || ''
    rb.sizeBytes = Number(view?.sizeBytes) || 0
    if (ok && !rb.path) return
  } else {
    rb.pages = view?.pages || []
    if (ok && !rb.pages.length) return
  }
  msg.blocks.push(rb)
}

export function buildMessages(events) {
  const out = []
  let msg = null

  const ensureAssistant = () => {
    const last = out[out.length - 1]
    if (last && last.role === 'assistant') return last
    msg = {
      id: uid('a'),
      role: 'assistant',
      blocks: [],
      usage: null,
      status: 'done',
      stopReason: null,
      error: null,
      createdAt: Date.now()
    }
    out.push(msg)
    return msg
  }

  for (const ev of events || []) {
    switch (ev?.type) {
      case 'user.message':
        out.push({ id: uid('u'), role: 'user', text: ev.content || '', createdAt: Date.now() })
        msg = null
        break
      case 'engine.thinking': {
        const m = ensureAssistant()
        m.blocks.push({ id: uid('b'), kind: 'thinking', text: ev.content || '', closed: true })
        break
      }
      case 'engine.message': {
        const m = ensureAssistant()
        const text = joinParts(ev.content)
        if (!text) break
        m.blocks.push({ id: uid('b'), kind: 'text', target: text, shown: text, closed: true })
        break
      }
      case 'engine.tool_use': {
        const m = ensureAssistant()
        m.blocks.push({
          id: uid('b'),
          kind: 'tool',
          toolUseId: ev.tool_use_id,
          name: ev.name,
          input: ev.input,
          args: safeJson(ev.input, null),
          state: 'running',
          content: '',
          structured: null,
          success: true,
          opened: false
        })
        break
      }
      case 'engine.tool_result': {
        let target = null
        for (let i = out.length - 1; i >= 0 && i >= out.length - 4; i--) {
          const m = out[i]
          if (m.role !== 'assistant') continue
          const hit = m.blocks.find((b) => b.kind === 'tool' && b.toolUseId === ev.tool_use_id)
          if (hit) {
            hit.state = ev.success === false ? 'error' : 'success'
            hit.success = ev.success !== false
            hit.content = ev.content || ''
            hit.structured = ev.structured_content || null
            target = m
            break
          }
        }
        if (target) pushReplayResult(target, ev)
        break
      }
      case 'session.usage': {
        if (msg) {
          msg.usage = {
            prompt: ev.prompt_tokens || 0,
            completion: ev.completion_tokens || 0,
            total: ev.total_tokens || 0
          }
        }
        break
      }
      case 'session.error': {
        const m = ensureAssistant()
        m.error = { message: ev.message, type: ev.error_type }
        m.status = 'error'
        break
      }
      case 'session.status': {
        if (msg && (ev.status === 'idle' || ev.status === 'terminated')) {
          msg.status = ev.status === 'terminated' ? 'terminated' : 'done'
          msg.stopReason = ev.stop_reason || null
        }
        break
      }
      default:
        break
    }
  }
  return out
}

/** content: [{type:'text', text}] → string */
export function joinParts(content) {
  if (!content) return ''
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return content
      .map((p) => (typeof p === 'string' ? p : p?.text || ''))
      .join('')
  }
  return String(content)
}
