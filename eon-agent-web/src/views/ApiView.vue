<script setup>
import { computed, onMounted, ref } from 'vue'
import AppHeader from '@/components/layout/AppHeader.vue'
import AppIcon from '@/components/common/AppIcon.vue'
import { useSessionStore } from '@/stores/session'
import { useSettingsStore } from '@/stores/settings'
import { useToastStore } from '@/stores/toast'
import { useUiStore } from '@/stores/ui'
import { listSessions, ping } from '@/api/agent'

const session = useSessionStore()
const settings = useSettingsStore()
const toast = useToastStore()
const ui = useUiStore()

const testing = ref(false)
const latency = ref(null)
const sample = ref('')

const API_BASE_TEXT = `${window.location.origin}/api （开发态由 Vite 代理转发到 Spring Boot 8080）`

const ENDPOINTS = [
  {
    method: 'POST',
    path: '/api/chat',
    desc: '流式对话。sessionId 为空时后端自动创建会话；响应为 text/event-stream，会话身份由第一帧 session.start 交付。',
    req: [
      ['X-User-Id', 'header', '用户标识请求头，缺省 default'],
      ['sessionId', 'string', '会话 ID，为空则新建'],
      ['message', 'string', '用户消息，必填'],
      ['kbId', 'number', '知识库 ID（预留）'],
      ['modelId', 'number', '模型配置 ID（预留）'],
      ['retryMessageId', 'number', '重试覆盖的消息 ID（预留）']
    ],
    res: 'SSE 事件流（见下方事件表）'
  },
  {
    method: 'POST',
    path: '/api/interrupt',
    desc: '中断指定会话正在执行的任务。',
    req: [['sessionId', 'string', '目标会话 ID']],
    res: '{ status: "interrupted" | "no_session" }'
  },
  {
    method: 'GET',
    path: '/api/sessions',
    desc: '查询用户的历史会话列表，按最后活跃时间倒序。',
    req: [['X-User-Id', 'header', '用户标识请求头，缺省 default']],
    res: '[{ index, sessionId, title, messageCount, lastActivityAt }]'
  },
  {
    method: 'GET',
    path: '/api/sessions/{sessionId}',
    desc: '回放会话账本，返回与实时 SSE 结构完全一致的事件数组（首帧同为 session.start）。',
    req: [
      ['sessionId', 'string', '路径参数'],
      ['X-User-Id', 'header', '用户标识请求头，缺省 default']
    ],
    res: '[{ type, timestamp, session_id, ... }]'
  },
  {
    method: 'DELETE',
    path: '/api/sessions/{sessionId}',
    desc: '删除会话索引并使缓存失效。',
    req: [
      ['sessionId', 'string', '路径参数'],
      ['X-User-Id', 'header', '用户标识请求头，缺省 default']
    ],
    res: '{ status: "deleted" | "not_found", session_id }'
  }
]

const EVENTS = [
  ['session.start', 'session_id / is_new / title', '首帧：交付服务端确定的会话身份'],
  ['session.status', 'status / stop_reason', '任务状态：running → idle / terminated'],
  ['engine.delta', 'turn_id / kind / delta', '流式增量，kind 为 text 或 thinking'],
  ['engine.thinking', 'turn_id / content', '完整思考块'],
  ['engine.message', 'turn_id / message_id / content[]', '本轮最终回答'],
  ['engine.tool_use', 'turn_id / tool_use_id / name / input', '请求调用工具'],
  ['engine.tool_result', 'tool_use_id / content / structured_content / success', '工具执行结果'],
  ['session.usage', 'prompt_tokens / completion_tokens / total_tokens', 'token 用量'],
  ['session.error', 'message / error_type', '执行错误'],
  ['user.message', 'content', '仅回放时出现，还原用户消息']
]

const METHOD_TONE = {
  POST: 'post',
  GET: 'get',
  DELETE: 'del'
}

const connText = computed(() =>
  session.connection === 'online' ? '在线' : session.connection === 'offline' ? '离线' : '检测中'
)

async function testConnection() {
  testing.value = true
  latency.value = null
  sample.value = ''
  try {
    const ms = await ping(settings.userId)
    latency.value = ms
    session.connection = 'online'
    const list = await listSessions(settings.userId)
    sample.value = JSON.stringify(list.slice(0, 2), null, 2)
    toast.success(`后端响应正常，耗时 ${ms}ms`)
  } catch (e) {
    session.connection = 'offline'
    sample.value = JSON.stringify({ status: 'error', type: e.type, message: e.message }, null, 2)
    toast.error(`连接失败：${e.message}`)
  } finally {
    testing.value = false
  }
}

function applyUserId() {
  const v = settings.userId.trim()
  if (!v) {
    toast.warn('用户标识不能为空')
    return
  }
  settings.userId = v
  session.loadSessions().catch(() => {})
  toast.success('用户标识已更新，会话列表已刷新')
}

onMounted(() => {
  if (session.connection === 'unknown') session.loadSessions().catch(() => {})
})
</script>

<template>
  <section class="page">
    <AppHeader title="接口与设置" subtitle="后端契约、连通性检测与前端偏好">
      <template #left>
        <button class="btn btn-ghost btn-icon menu-btn" title="打开侧边栏" @click="ui.toggleDrawer()">
          <AppIcon name="menu" :size="18" />
        </button>
      </template>
      <template #actions>
        <button class="btn btn-sm" :disabled="testing" @click="testConnection">
          <AppIcon :name="testing ? 'loader' : 'zap'" :size="14" :class="{ spin: testing }" />
          {{ testing ? '检测中' : '连通性检测' }}
        </button>
      </template>
    </AppHeader>

    <div class="page__body">
      <!-- 连接状态 -->
      <div class="panel card">
        <div class="conn">
          <span class="conn__dot" :class="`conn__dot--${session.connection}`" />
          <div class="conn__text">
            <h3 class="conn__title">
              后端服务 <span :class="session.connection === 'online' ? 'ok' : 'err'">{{ connText }}</span>
            </h3>
            <p class="conn__desc mono">{{ API_BASE_TEXT }}</p>
          </div>
          <div v-if="latency !== null" class="conn__latency">
            <span class="conn__ms">{{ latency }}<i>ms</i></span>
            <span class="conn__ms-label">响应耗时</span>
          </div>
        </div>

        <pre v-if="sample" class="sample mono">{{ sample }}</pre>

        <p v-if="session.connection === 'offline'" class="hint">
          <AppIcon name="alert" :size="13" />
          请确认 Spring Boot 已启动（默认 8080），或调整 vite 代理目标后重启前端。
        </p>
      </div>

      <!-- 设置 -->
      <div class="panel card">
        <div class="panel__head">
          <h3 class="panel__title"><AppIcon name="sliders" :size="15" /> 前端偏好</h3>
          <p class="panel__desc">设置会保存在本地浏览器，刷新后依然生效</p>
        </div>

        <div class="setting">
          <div class="setting__label">
            <span>用户标识 userId</span>
            <em>后端按此字段隔离会话索引，需与后端一致才能看到历史会话</em>
          </div>
          <div class="setting__control">
            <input v-model="settings.userId" class="input" placeholder="default" />
            <button class="btn btn-sm btn-primary" @click="applyUserId">应用</button>
          </div>
        </div>

        <div class="setting">
          <div class="setting__label">
            <span>打字机速度</span>
            <em>{{ settings.typeSpeed }} 字符 / 秒（流式输出渲染节奏）</em>
          </div>
          <div class="setting__control">
            <input v-model.number="settings.typeSpeed" type="range" min="80" max="1200" step="20" class="range" />
          </div>
        </div>

        <div class="setting">
          <div class="setting__label">
            <span>自动滚动</span>
            <em>生成过程中保持视口贴在最新内容</em>
          </div>
          <div class="setting__control">
            <button class="switch" :class="{ 'is-on': settings.autoScroll }" @click="settings.autoScroll = !settings.autoScroll">
              <span class="switch__knob" />
            </button>
          </div>
        </div>

        <div class="setting">
          <div class="setting__label">
            <span>默认展开思考过程</span>
            <em>Engine 的 reasoning 内容是否默认可见</em>
          </div>
          <div class="setting__control">
            <button class="switch" :class="{ 'is-on': settings.expandThinking }" @click="settings.expandThinking = !settings.expandThinking">
              <span class="switch__knob" />
            </button>
          </div>
        </div>

        <div class="setting">
          <div class="setting__label">
            <span>工具卡片默认折叠</span>
            <em>工具调用的入参与结果是否默认收起</em>
          </div>
          <div class="setting__control">
            <button class="switch" :class="{ 'is-on': settings.collapseToolResult }" @click="settings.collapseToolResult = !settings.collapseToolResult">
              <span class="switch__knob" />
            </button>
          </div>
        </div>

        <button class="btn btn-sm" @click="settings.reset()">
          <AppIcon name="refresh" :size="13" /> 恢复默认
        </button>
      </div>

      <!-- 接口列表 -->
      <div class="panel card">
        <div class="panel__head">
          <h3 class="panel__title"><AppIcon name="terminal" :size="15" /> REST 接口</h3>
          <p class="panel__desc">全部接口均位于 /api 前缀下，由 AgentController 暴露</p>
        </div>

        <div class="endpoints">
          <article v-for="e in ENDPOINTS" :key="e.path" class="endpoint">
            <header class="endpoint__head">
              <span class="method" :class="`method--${METHOD_TONE[e.method]}`">{{ e.method }}</span>
              <code class="endpoint__path mono">{{ e.path }}</code>
            </header>
            <p class="endpoint__desc">{{ e.desc }}</p>

            <div class="endpoint__grid">
              <div>
                <h5 class="endpoint__sub">请求</h5>
                <table class="table">
                  <tbody>
                    <tr v-for="r in e.req" :key="r[0]">
                      <td class="mono">{{ r[0] }}</td>
                      <td class="dim">{{ r[1] }}</td>
                      <td>{{ r[2] }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div>
                <h5 class="endpoint__sub">响应</h5>
                <pre class="endpoint__res mono">{{ e.res }}</pre>
              </div>
            </div>
          </article>
        </div>
      </div>

      <!-- 事件表 -->
      <div class="panel card">
        <div class="panel__head">
          <h3 class="panel__title"><AppIcon name="activity" :size="15" /> SSE 事件类型</h3>
          <p class="panel__desc">event 名为事件类型，data 为 JSON 负载；每个事件都带 session_id</p>
        </div>

        <div class="table-wrap">
          <table class="table table--events">
            <thead>
              <tr>
                <th>事件名（event）</th>
                <th>主要字段</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="ev in EVENTS" :key="ev[0]">
                <td class="mono ev-name">{{ ev[0] }}</td>
                <td class="mono dim">{{ ev[1] }}</td>
                <td>{{ ev[2] }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  flex: 1;
}
.page__body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.panel {
  padding: 18px;
}
.panel__head {
  margin-bottom: 14px;
}
.panel__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 650;
}
.panel__desc {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-muted);
}

/* 连接 */
.conn {
  display: flex;
  align-items: center;
  gap: 14px;
}
.conn__dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--text-muted);
  flex: none;
}
.conn__dot--online {
  background: var(--ok);
  box-shadow: 0 0 0 4px rgba(52, 211, 153, 0.16);
}
.conn__dot--offline {
  background: var(--err);
  box-shadow: 0 0 0 4px rgba(251, 113, 133, 0.16);
}
.conn__title {
  margin: 0 0 3px;
  font-size: 15px;
  font-weight: 650;
}
.ok {
  color: var(--ok);
}
.err {
  color: var(--err);
}
.conn__desc {
  margin: 0;
  font-size: 11.8px;
  color: var(--text-muted);
}
.conn__latency {
  margin-left: auto;
  text-align: right;
}
.conn__ms {
  font-size: 20px;
  font-weight: 700;
  color: #67e8f9;
}
.conn__ms i {
  font-size: 11px;
  font-style: normal;
  color: var(--text-muted);
  margin-left: 2px;
}
.conn__ms-label {
  display: block;
  font-size: 11px;
  color: var(--text-muted);
}

.sample {
  margin: 14px 0 0;
  padding: 12px;
  border-radius: 12px;
  background: rgba(0, 0, 0, 0.32);
  border: 1px solid var(--border);
  font-size: 11.8px;
  line-height: 1.7;
  max-height: 200px;
  overflow: auto;
  color: var(--text-soft);
}

.hint {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 12px 0 0;
  font-size: 12.3px;
  color: #fda4af;
}

/* 设置项 */
.setting {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 0;
  border-top: 1px solid var(--border);
}
.setting:first-of-type {
  border-top: none;
}
.setting__label {
  flex: 1;
  min-width: 0;
}
.setting__label span {
  font-size: 13.5px;
  font-weight: 500;
}
.setting__label em {
  display: block;
  font-style: normal;
  font-size: 11.8px;
  color: var(--text-muted);
  margin-top: 2px;
}
.setting__control {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
.setting__control .input {
  width: 190px;
}

.range {
  width: 200px;
  accent-color: #8b5cf6;
}

.switch {
  width: 42px;
  height: 24px;
  border-radius: 99px;
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.07);
  cursor: pointer;
  position: relative;
  transition: background 0.22s, border-color 0.22s;
}
.switch.is-on {
  background: var(--grad-brand);
  border-color: transparent;
}
.switch__knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
  transition: transform 0.22s var(--ease);
}
.switch.is-on .switch__knob {
  transform: translateX(18px);
}

/* 接口 */
.endpoints {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.endpoint {
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  padding: 14px;
  background: rgba(255, 255, 255, 0.025);
  transition: border-color 0.2s, background 0.2s;
}
.endpoint:hover {
  border-color: var(--border-strong);
  background: rgba(255, 255, 255, 0.045);
}
.endpoint__head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.method {
  font-size: 10.5px;
  font-weight: 700;
  padding: 3px 8px;
  border-radius: 6px;
  letter-spacing: 0.04em;
  flex: none;
}
.method--post {
  background: rgba(52, 211, 153, 0.16);
  color: #6ee7b7;
}
.method--get {
  background: rgba(96, 165, 250, 0.16);
  color: #93c5fd;
}
.method--del {
  background: rgba(251, 113, 133, 0.16);
  color: #fda4af;
}
.endpoint__path {
  font-size: 13px;
  font-weight: 600;
}
.endpoint__desc {
  margin: 0 0 12px;
  font-size: 12.5px;
  color: var(--text-soft);
  line-height: 1.7;
}
.endpoint__grid {
  display: grid;
  grid-template-columns: 1.3fr 1fr;
  gap: 16px;
}
.endpoint__sub {
  margin: 0 0 6px;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-muted);
}
.endpoint__res {
  margin: 0;
  padding: 9px 11px;
  border-radius: 9px;
  background: rgba(0, 0, 0, 0.3);
  border: 1px solid var(--border);
  font-size: 11.5px;
  line-height: 1.65;
  color: #a5b4fc;
  white-space: pre-wrap;
}

/* 表格 */
.table-wrap {
  overflow-x: auto;
}
.table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.3px;
}
.table td,
.table th {
  padding: 6px 10px;
  text-align: left;
  border-bottom: 1px solid var(--border);
  vertical-align: top;
}
.table th {
  color: var(--text-muted);
  font-weight: 600;
  font-size: 11.5px;
  white-space: nowrap;
}
.dim {
  color: var(--text-muted);
}
.ev-name {
  color: #67e8f9;
  white-space: nowrap;
}

.spin {
  animation: spin 1s linear infinite;
}

@media (max-width: 780px) {
  .endpoint__grid {
    grid-template-columns: 1fr;
  }
  .setting {
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
  }
  .setting__control .input,
  .range {
    width: 100%;
  }
}
</style>
