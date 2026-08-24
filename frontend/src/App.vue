<script setup>
import { ref, watch, nextTick, onMounted, computed } from 'vue'
import { store, refreshSessions, showToast } from './stores/app'
import { api } from './api'
import Sidebar from './components/Sidebar.vue'
import MessageBubble from './components/MessageBubble.vue'
import InputArea from './components/InputArea.vue'
import InteractionPanel from './components/InteractionPanel.vue'
import TestMode from './TestMode.vue'
import Icon from './components/Icon.vue'

const isTest = new URLSearchParams(location.search).get('test') === '1'

const messagesContainer = ref(null)
const healthStatus = ref('checking') // checking | online | offline

// 自动滚动到底部
watch(
  () => store.messages.length,
  async () => {
    await nextTick()
    scrollToBottom()
  }
)

// 监听最后一条消息内容变化（流式更新时滚动）
watch(
  () => store.messages.map((m) => m.content).join(''),
  async () => {
    await nextTick()
    scrollToBottom(true)
  }
)

function scrollToBottom(smooth = false) {
  const el = messagesContainer.value
  if (!el) return
  el.scrollTo({
    top: el.scrollHeight,
    behavior: smooth ? 'smooth' : 'auto',
  })
}

// 健康检查
async function checkHealth() {
  try {
    await api.health()
    healthStatus.value = 'online'
  } catch (e) {
    healthStatus.value = 'offline'
  }
}

onMounted(() => {
  checkHealth()
  setInterval(checkHealth, 30000)
  refreshSessions().catch(() => {})
})

const hasMessages = computed(() => store.messages.length > 0)
const currentSessionId = computed(() => store.currentSessionId)
</script>

<template>
  <div class="app-layout">
    <!-- 测试模式数据注入 -->
    <TestMode v-if="isTest" />

    <!-- 背景光晕 -->
    <div class="bg-glow"></div>

    <!-- 侧边栏 -->
    <Sidebar />

    <!-- 主区域 -->
    <main class="main">
      <!-- 顶栏 -->
      <header class="topbar">
        <div class="topbar-left">
          <h2 class="page-title">
            {{ currentSessionId ? '对话' : '欢迎使用 Eon Agent' }}
          </h2>
          <p v-if="currentSessionId" class="session-id-display">
            {{ currentSessionId }}
          </p>
        </div>
        <div class="topbar-right">
          <div :class="['health-pill', healthStatus]">
            <span class="health-dot"></span>
            <span>{{ healthStatus === 'online' ? '服务在线' : healthStatus === 'offline' ? '服务离线' : '检测中' }}</span>
          </div>
        </div>
      </header>

      <!-- 消息流 -->
      <div ref="messagesContainer" class="messages-container">
        <!-- 空状态 -->
        <div v-if="!hasMessages" class="empty-state">
          <div class="empty-logo">
            <span class="empty-mark">E</span>
            <div class="empty-glow"></div>
          </div>
          <h2 class="empty-title">Eon Agent</h2>
          <p class="empty-subtitle">基于 LangChain4j 的自主智能体工作台</p>
          <div class="empty-features">
            <div class="feature-item">
              <span class="feature-icon"><Icon name="wrench" :size="20" /></span>
              <div>
                <div class="feature-name">工具调用</div>
                <div class="feature-desc">12 个内置工具 + MCP 扩展</div>
              </div>
            </div>
            <div class="feature-item">
              <span class="feature-icon"><Icon name="radio" :size="20" /></span>
              <div>
                <div class="feature-name">SSE 流式</div>
                <div class="feature-desc">实时推送思考与工具执行</div>
              </div>
            </div>
            <div class="feature-item">
              <span class="feature-icon"><Icon name="brain" :size="20" /></span>
              <div>
                <div class="feature-name">上下文压缩</div>
                <div class="feature-desc">三级递进，长会话不爆</div>
              </div>
            </div>
            <div class="feature-item">
              <span class="feature-icon"><Icon name="zap" :size="20" /></span>
              <div>
                <div class="feature-name">优雅停止</div>
                <div class="feature-desc">预算控制 + 死循环检测</div>
              </div>
            </div>
          </div>
          <p class="empty-hint">在下方输入消息开始对话</p>
        </div>

        <!-- 消息列表 -->
        <div v-else class="messages-list">
          <MessageBubble
            v-for="msg in store.messages"
            :key="msg.id"
            :message="msg"
          />

          <!-- 交互面板（AskQuestion） -->
          <InteractionPanel
            v-if="currentSessionId"
            :session-id="currentSessionId"
          />
        </div>
      </div>

      <!-- 输入区 -->
      <InputArea />
    </main>

    <!-- Toast -->
    <Transition name="toast">
      <div v-if="store.toast" :class="['toast', store.toast.type]">
        <Icon
          :name="store.toast.type === 'error' ? 'alert' : store.toast.type === 'success' ? 'check-circle' : 'info'"
          :size="16"
        />
        <span>{{ store.toast.message }}</span>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
  position: relative;
}

/* 背景光晕 */
.bg-glow {
  position: fixed;
  inset: 0;
  background: var(--grad-glow);
  pointer-events: none;
  z-index: 0;
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  position: relative;
  z-index: 1;
}

/* 顶栏 */
.topbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 28px;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--bg-glass);
  backdrop-filter: blur(20px);
}
.topbar-left { min-width: 0; }
.page-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--t-primary);
  margin: 0;
}
.session-id-display {
  font-size: 11px;
  color: var(--t-tertiary);
  font-family: 'JetBrains Mono', monospace;
  margin: 2px 0 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.health-pill {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 500;
  background: var(--bg-surface);
  border: 1px solid var(--border-default);
}
.health-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.health-pill.online .health-dot {
  background: var(--c-success);
  box-shadow: 0 0 8px var(--c-success);
}
.health-pill.offline .health-dot {
  background: var(--c-danger);
  box-shadow: 0 0 8px var(--c-danger);
}
.health-pill.checking .health-dot {
  background: var(--c-warning);
  animation: pulse 1s infinite;
}
.health-pill.online { color: var(--c-success-light); }
.health-pill.offline { color: var(--c-danger-light); }
.health-pill.checking { color: var(--c-warning-light); }

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

/* 消息容器 */
.messages-container {
  flex: 1;
  overflow-y: auto;
  scroll-behavior: smooth;
}
.messages-container::-webkit-scrollbar { width: 8px; }
.messages-container::-webkit-scrollbar-track { background: transparent; }
.messages-container::-webkit-scrollbar-thumb {
  background: var(--border-default);
  border-radius: 4px;
}
.messages-container::-webkit-scrollbar-thumb:hover {
  background: var(--border-strong);
}

.messages-list {
  max-width: 880px;
  margin: 0 auto;
  padding: 24px 28px;
}

/* 空状态 */
.empty-state {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px;
  text-align: center;
}
.empty-logo {
  width: 80px;
  height: 80px;
  border-radius: 22px;
  background: var(--grad-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  margin-bottom: 24px;
  box-shadow: 0 8px 32px rgba(99, 102, 241, 0.4);
}
.empty-mark {
  font-family: 'Georgia', serif;
  font-size: 44px;
  font-weight: 800;
  color: white;
}
.empty-glow {
  position: absolute;
  inset: -20px;
  background: radial-gradient(circle, rgba(99, 102, 241, 0.3), transparent 70%);
  border-radius: 50%;
  z-index: -1;
  animation: glow 3s ease-in-out infinite;
}
@keyframes glow {
  0%, 100% { opacity: 0.5; transform: scale(1); }
  50% { opacity: 1; transform: scale(1.1); }
}
.empty-title {
  font-size: 32px;
  font-weight: 700;
  color: var(--t-primary);
  margin: 0 0 8px;
  background: var(--grad-primary);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.empty-subtitle {
  font-size: 14px;
  color: var(--t-tertiary);
  margin: 0 0 40px;
}
.empty-features {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px;
  max-width: 560px;
  width: 100%;
  margin-bottom: 32px;
}
.feature-item {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 14px 16px;
  background: var(--bg-glass);
  backdrop-filter: blur(12px);
  border: 1px solid var(--border-default);
  border-radius: var(--r-md);
  text-align: left;
  transition: all var(--dur-fast);
}
.feature-item:hover {
  border-color: var(--border-primary);
  transform: translateY(-2px);
}
.feature-icon {
  font-size: 22px;
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-surface);
  border-radius: 10px;
  flex-shrink: 0;
}
.feature-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--t-primary);
}
.feature-desc {
  font-size: 11px;
  color: var(--t-tertiary);
  margin-top: 2px;
}
.empty-hint {
  font-size: 13px;
  color: var(--t-tertiary);
  margin: 0;
}

/* Toast */
.toast {
  position: fixed;
  bottom: 100px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 20px;
  border-radius: var(--r-md);
  font-size: 13px;
  font-weight: 500;
  background: var(--bg-glass-strong);
  backdrop-filter: blur(20px);
  border: 1px solid var(--border-default);
  z-index: 100;
  box-shadow: var(--shadow-lg);
}
.toast.error {
  border-color: rgba(239, 68, 68, 0.4);
  color: var(--c-danger-light);
}
.toast.success {
  border-color: rgba(16, 185, 129, 0.4);
  color: var(--c-success-light);
}
.toast.info {
  border-color: var(--border-default);
  color: var(--t-primary);
}
.toast-icon { font-size: 16px; }

.toast-enter-active, .toast-leave-active {
  transition: all 0.3s ease;
}
.toast-enter-from, .toast-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(20px);
}
</style>
