/**
 * 会话与消息状态中心。一条 assistant 消息 = blocks[]（thinking / text / tool / file / web），保证时序交错。
 *
 * 运行时按会话隔离（runs: sessionId → 运行时），因此切换会话不会打断后台任务：
 * 事件按 session_id 写进各自的运行时，切回来直接看到实时内容。
 * 实时流与历史回放共用同一套事件 → 消息还原逻辑。
 */
import { defineStore } from 'pinia'
import { computed, markRaw, reactive, ref } from 'vue'
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

/**
 * 收敛后端透传的问题结构：去重 id、剔除不合法项，让卡片渲染不必到处判空。
 * 没有任何有效问题时返回 null，避免出现空卡片。
 */
function normalizeQuestion(data) {
  const raw = Array.isArray(data?.questions) ? data.questions : []
  const questions = raw
    .filter((q) => q && q.prompt)
    .map((q, i) => ({
      id: String(q.id ?? `q${i}`),
      prompt: String(q.prompt),
      allowMultiple: Boolean(q.allow_multiple),
      options: (Array.isArray(q.options) ? q.options : [])
        .filter((o) => o && o.label != null)
        .map((o, j) => ({ id: String(o.id ?? `o${j}`), label: String(o.label) }))
    }))
  return questions.length ? { title: String(data.title || ''), questions } : null
}

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

/**
 * 未落地会话的临时键前缀：每次 send 分配一个唯一键（__new__1、__new__2…）。
 * 必须是「每次 send 一份」而不是单个全局键——并发的多条流共用同一个 applyEvent，
 * 靠全局键反查会让任意一条流都能认领走别人的临时运行时。
 */
const NEW_PREFIX = '__new__'

/** 未选中任何会话时的空壳，避免消费方到处判空 */
const EMPTY_RUN = {
  messages: [],
  streaming: false,
  status: 'idle',
  lastError: null,
  elapsed: 0,
  hookPhase: '',
  pendingQuestion: null,
  todos: []
}

export const useSessionStore = defineStore('session', () => {
  const settings = useSettingsStore()
  const toast = useToastStore()

  /* ── 全局状态 ─────────────────────────── */
  const sessions = ref([])
  const sessionsLoading = ref(false)
  const currentId = ref('')
  const loadingSession = ref(false)
  const connection = ref('unknown')

  /* ── 会话运行时（按 id 隔离） ─────────── */
  const runs = reactive({})

  /** 未落地 send 的递增序号，用来生成互不冲突的临时键 */
  let pendingSeq = 0
  /** 最新发起且身份尚未落地的临时键：决定空白页显示哪一次 pending */
  const latestPendingKey = ref('')
  /** 列表刷新序号：并发刷新时只认最后一次发出的请求 */
  let listSeq = 0

  /**
   * 取指定会话的运行时。
   * 赋值后要从 runs 取回代理再返回——直接改新建时的原始对象不会触发响应式更新。
   */
  function runOf(id, create = true) {
    if (!id) return null
    let r = runs[id]
    if (!r && create) {
      runs[id] = {
        messages: [],
        streaming: false,
        status: 'idle', // idle | running | terminated
        run: null, // { abort } 流式句柄
        assistant: null, // 当前正在生成的 assistant 消息
        errored: false,
        elapsed: 0,
        timer: null,
        lastError: null,
        hookPhase: '',
        pendingQuestion: null,
        todos: []
      }
      r = runs[id]
    }
    return r
  }

  /**
   * 当前查看的会话运行时。
   * 未选中会话时回退到最新发起的那次 pending——否则发出第一条消息到身份落地之间
   * 会闪一下空白（甚至显示欢迎页）。回退只认 latestPendingKey，
   * 保证连发多条时视图停在用户最后发起的那条上。
   */
  const active = computed(() => {
    if (currentId.value) return runs[currentId.value] || EMPTY_RUN
    const k = latestPendingKey.value
    return (k && runs[k]) || EMPTY_RUN
  })
  /** 正在运行的会话 id，供侧边栏标记（临时键不算会话） */
  const runningIds = computed(() =>
    Object.keys(runs).filter((id) => !id.startsWith(NEW_PREFIX) && runs[id].streaming)
  )

  /* ── 当前会话投影（消费方只读） ───────── */
  const messages = computed(() => active.value.messages)
  const streaming = computed(() => active.value.streaming)
  const sessionStatus = computed(() => active.value.status)
  const lastError = computed(() => active.value.lastError)
  const elapsed = computed(() => active.value.elapsed)
  const hookPhase = computed(() => active.value.hookPhase)
  const pendingQuestion = computed(() => active.value.pendingQuestion)
  const todos = computed(() => active.value.todos)

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
  /**
   * 会话列表。多个会话同时结束会并发触发刷新，先发起的请求可能后到达并带回更旧的快照，
   * 因此只认最后一次发出的那次，过期响应直接丢弃。
   */
  async function loadSessions() {
    const seq = ++listSeq
    sessionsLoading.value = true
    try {
      const list = await api.listSessions(settings.userId)
      if (seq !== listSeq) return
      sessions.value = list
      connection.value = 'online'
    } catch (e) {
      if (seq === listSeq) connection.value = 'offline'
      throw e
    } finally {
      if (seq === listSeq) sessionsLoading.value = false
    }
  }

  /**
   * 采纳事件流携带的会话身份，把这次 run 的临时运行时迁到真实 id 下
   * （对象引用不变，回调闭包里持有的引用依然有效）。
   *
   * ctx 由发起这次 run 的 send 闭包捕获，只有它知道自己的临时运行时是哪一份。
   * 后台会话的流拿不到 ctx，也就无从碰别人的 pending——这是身份错配的唯一防线。
   */
  function adoptSession(id, sessionTitle, ctx) {
    if (!id || !ctx?.pendingKey) return
    const key = ctx.pendingKey
    const pending = ctx.run
    // 这份 pending 已被丢弃（流结束都没拿到 id）时不再落地
    if (runs[key] !== pending) return

    runs[id] = pending
    delete runs[key]
    ctx.pendingKey = ''

    // 只有最新发起的那次才接管视图：连发两条时先落地的那条不该把视图抢走。
    // 更早那条仍然正常落地进列表，用户从侧边栏可以切过去，内容不丢。
    const isLatest = latestPendingKey.value === key
    if (isLatest) latestPendingKey.value = ''
    // 等待身份期间用户可能已切到别的会话，此时只落地运行时，不动当前视图
    if (isLatest && !currentId.value) currentId.value = id

    const placeholder = {
      sessionId: id,
      title: sessionTitle || deriveTitle(lastUserText(pending)),
      messageCount: 1,
      lastActivityAt: new Date().toISOString()
    }
    sessions.value = [placeholder, ...sessions.value.filter((s) => s.sessionId !== id)]
  }

  /**
   * 丢弃一次未落地的 pending：流已结束却始终没拿到真实 id（请求失败或被中断）。
   * 此时临时运行时再没人认领，必须清掉，否则会永久挂在 runs 里。
   */
  function dropPending(ctx) {
    if (!ctx?.pendingKey) return
    const key = ctx.pendingKey
    if (runs[key] === ctx.run) delete runs[key]
    if (latestPendingKey.value === key) latestPendingKey.value = ''
    ctx.pendingKey = ''
  }

  /**
   * 新建会话：不停止任何任务，当前会话若在运行就留在后台继续跑。
   * 未落地的 pending 也不再丢弃——它已经在后端跑起来了，照常落地进列表即可。
   */
  function newSession() {
    currentId.value = ''
    latestPendingKey.value = ''
  }

  async function openSession(id, force = false) {
    if (!id) return
    if (!force && id === currentId.value) return
    loadingSession.value = true
    try {
      const existing = runs[id]
      if (existing) {
        // 已在内存（多半正在运行）：直接切过去看实时内容，不回放账本
        existing.lastError = null
      } else {
        const events = await api.getSessionEvents(id, settings.userId)
        const created = runOf(id)
        created.messages = buildMessages(events)
        created.status = 'idle'
        created.pendingQuestion = null
        created.todos = []
      }
      currentId.value = id
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
      // 还在跑就先掐断，否则后台会继续往已删除的目录写数据
      const r = runs[id]
      if (r?.streaming) {
        try {
          await api.interruptSession(id, settings.userId)
        } catch {
          /* 忽略 */
        }
        r.run?.abort('session_deleted')
      }
      delete runs[id]
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
    if (!content) return

    // 新会话先用独立临时键占位，首帧 session.start 到达后迁到真实 id
    const pendingKey = currentId.value ? '' : `${NEW_PREFIX}${++pendingSeq}`
    const r = runOf(currentId.value || pendingKey)
    if (r.streaming) return

    // 本次 run 的身份由闭包持有：applyEvent 是所有流共用的同一个函数，
    // 只有发起方的闭包能说清「这份临时运行时属于哪条流」
    const ctx = pendingKey ? { pendingKey, run: r } : null
    if (pendingKey) latestPendingKey.value = pendingKey

    r.lastError = null
    r.errored = false
    r.pendingQuestion = null

    r.messages.push({ id: uid('u'), role: 'user', text: content, createdAt: Date.now() })
    r.messages.push({
      id: uid('a'),
      role: 'assistant',
      blocks: [],
      usage: null,
      status: 'streaming',
      stopReason: null,
      error: null,
      createdAt: Date.now()
    })
    r.assistant = r.messages[r.messages.length - 1]

    r.streaming = true
    r.status = 'running'
    r.elapsed = 0
    r.timer = setInterval(() => {
      r.elapsed += 1
    }, 1000)

    const stream = api.chatStream(
      { sessionId: currentId.value || null, message: content, userId: settings.userId },
      {
        onEvent: (ev) => applyEvent(ev, ctx),
        onError: (err) => {
          dropPending(ctx)
          r.lastError = err
          if (err.type !== 'aborted') {
            toast.error(err.message || '连接异常')
            if (r.assistant) r.assistant.error = { message: err.message, type: err.type }
          }
          finishRun(r, err.type === 'aborted' ? 'stopped' : 'error', err.message)
        },
        onDone: ({ ok }) => {
          // 走到这里仍未落地说明这一轮没拿到真实 id，临时运行时不能再挂着
          dropPending(ctx)
          if (ok && r.status === 'running') finishRun(r, r.errored ? 'error' : 'done')
        }
      }
    )
    r.run = markRaw(stream)

    stream.promise.catch(() => {})
  }

  /** 停止生成：只作用于当前查看的会话，后台会话不受影响 */
  async function stop() {
    const id = currentId.value
    if (!id) return
    const r = runs[id]
    if (!r || !r.streaming) return
    try {
      await api.interruptSession(id, settings.userId)
    } catch {
      /* 忽略：即便后端无会话，本地也要断开 */
    }
    r.run?.abort('user_stop')
    r.run = null
    r.pendingQuestion = null
  }

  /**
   * 提交问题答案。答案投递给阻塞中的那次 ask_question——本轮 run 不中断，
   * 后端把它作为工具结果回填上下文后继续跑，所以这里只管清卡片，流仍在进行中。
   */
  async function answer(answers) {
    const r = runOf(currentId.value, false)
    const q = r?.pendingQuestion
    if (!q) return
    const payload = q.questions.map((item) => {
      const picked = answers[item.id] || {}
      return { id: item.id, labels: picked.labels || [], other: picked.other || '' }
    })
    const status = await api.answerQuestion(currentId.value, payload, settings.userId)
    if (status !== 'answered') {
      toast.error('提交失败：该提问已超时或已被中断')
      return
    }
    r.pendingQuestion = null
  }

  /* ── 事件 → 消息 ──────────────────────── */
  function lastAssistant(r) {
    for (let i = r.messages.length - 1; i >= 0; i--) {
      if (r.messages[i].role === 'assistant') return r.messages[i]
    }
    return null
  }

  function currentMsg(r, create = true) {
    const last = r.messages[r.messages.length - 1]
    if (last && last.role === 'assistant') return last
    if (!create) return null
    r.messages.push({
      id: uid('a'),
      role: 'assistant',
      blocks: [],
      usage: null,
      status: 'streaming',
      stopReason: null,
      error: null,
      createdAt: Date.now()
    })
    return r.messages[r.messages.length - 1]
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
    return pushBlock(msg, makeToolBlock(data.tool_use_id, data.name, data.input))
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
  function applyToolResult(r, msg, data) {
    fillTool(r, msg, data)
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

  function fillTool(r, msg, data) {
    // 工具结果可能落在上一条 assistant 消息（跨轮），向前回溯 3 条
    const candidates = [msg, ...(r?.messages || []).slice(-4, -1).reverse()]
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

  /**
   * @param ctx 发起这次 run 的 send 闭包捕获的身份。后台会话的流没有 ctx，
   *            因此它们的事件只会路由到自己的运行时，不会去认领别人的 pending。
   */
  function applyEvent({ name, data }, ctx = null) {
    if (!data) return
    // 每个事件都带 session_id，但只有发起方的 ctx 能触发身份落地
    adoptSession(data.session_id, data.title, ctx)
    // 事件路由到它自己的会话：后台会话照常推进，不污染当前视图。
    // 目标运行时缺失时按 session_id 就地建（例如新建会话被切走后身份才落地），避免事件被丢弃。
    const r = data.session_id ? runOf(data.session_id) : runOf(currentId.value, false)
    if (!r) return
    // 钩子阶段只是「当前状态」，收到任何其他事件即视为该阶段已结束
    r.hookPhase = name === 'engine.hook' ? data.hook || '' : ''
    switch (name) {
      case 'session.status': {
        if (data.status === 'running') {
          r.streaming = true
          r.status = 'running'
        } else if (data.status === 'terminated') {
          r.status = 'terminated'
          finishRun(r, 'terminated', data.stop_reason)
        } else if (data.status === 'idle') {
          finishRun(r, r.errored ? 'error' : 'done', data.stop_reason)
        }
        break
      }
      case 'engine.delta': {
        const msg = currentMsg(r)
        if (data.kind === 'thinking') appendThinking(msg, data.delta)
        else appendText(msg, data.delta)
        break
      }
      case 'engine.thinking': {
        appendThinking(currentMsg(r), data.content)
        break
      }
      case 'engine.message': {
        setFinalText(currentMsg(r), joinParts(data.content))
        break
      }
      case 'engine.tool_delta': {
        applyToolDelta(currentMsg(r), data)
        break
      }
      case 'engine.tool_use': {
        applyToolUse(currentMsg(r), data)
        break
      }
      case 'engine.tool_result': {
        applyToolResult(r, currentMsg(r), data)
        break
      }
      case 'session.question': {
        r.pendingQuestion = normalizeQuestion(data)
        break
      }
      case 'session.todo': {
        // 事件带的是 TodoStore 全量状态，直接覆盖；空数组表示待办已全部完成
        r.todos = Array.isArray(data.todos) ? data.todos.filter((t) => t && t.content) : []
        break
      }
      case 'session.usage': {
        currentMsg(r).usage = {
          prompt: data.prompt_tokens || 0,
          completion: data.completion_tokens || 0,
          total: data.total_tokens || 0
        }
        break
      }
      case 'session.error': {
        r.errored = true
        currentMsg(r).error = { message: data.message, type: data.error_type }
        break
      }
      default:
        break
    }
  }

  /* ── 收尾 ─────────────────────────────── */
  function finishRun(r, status, stopReason = null) {
    if (r.timer) {
      clearInterval(r.timer)
      r.timer = null
    }
    const msg = r.assistant || lastAssistant(r)
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
    r.assistant = null
    r.streaming = false
    r.status = status === 'terminated' ? 'terminated' : 'idle'
    r.run = null
    r.hookPhase = ''

    // 收尾后刷新列表（标题 / 消息数 / 活跃时间）
    refreshAfterRun()
  }

  /** 收尾后刷新列表（标题 / 消息数 / 活跃时间）。并发保护与失败静默都在 loadSessions 里。 */
  function refreshAfterRun() {
    loadSessions().catch(() => {})
  }

  function lastUserText(r) {
    for (let i = r.messages.length - 1; i >= 0; i--) {
      if (r.messages[i].role === 'user') return r.messages[i].text
    }
    return ''
  }

  return {
    sessions,
    sessionsLoading,
    currentId,
    runningIds,
    messages,
    streaming,
    sessionStatus,
    loadingSession,
    lastError,
    connection,
    elapsed,
    hookPhase,
    pendingQuestion,
    todos,
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
    answer
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
