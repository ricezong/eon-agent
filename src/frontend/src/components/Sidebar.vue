<script setup>
import { store, createNewSession, selectSession, deleteSession, refreshSessions, getSessionMessageCount } from '../stores/app'
import { formatTime } from '../utils/markdown'
import { onMounted } from 'vue'
import Icon from './Icon.vue'

onMounted(() => {
  refreshSessions().catch(() => {})
})
</script>

<template>
  <aside class="sidebar">
    <!-- Logo 区 -->
    <div class="logo">
      <div class="logo-mark">
        <span class="logo-text">E</span>
        <div class="logo-glow"></div>
      </div>
      <div class="logo-info">
        <h1 class="logo-title">Eon Agent</h1>
        <p class="logo-sub">智能体工作台</p>
      </div>
    </div>

    <!-- 新建会话 -->
    <button class="new-session-btn" @click="createNewSession()">
      <Icon name="plus" :size="18" />
      <span>新建对话</span>
    </button>

    <!-- 会话列表 -->
    <div class="sessions">
      <div class="sessions-header">
        <span>会话列表</span>
        <span class="sessions-count" v-if="store.sessions.length">{{ store.sessions.length }}</span>
      </div>

      <div v-if="store.sessionsLoading" class="sessions-empty">加载中…</div>

      <div v-else-if="!store.sessions.length" class="sessions-empty">
        <p>暂无活跃会话</p>
        <p class="hint">点击上方按钮开始</p>
      </div>

      <ul v-else class="session-list">
        <li
          v-for="s in store.sessions"
          :key="s.sessionId"
          :class="['session-item', { active: s.sessionId === store.currentSessionId }]"
          @click="selectSession(s.sessionId)"
        >
          <div class="session-icon">
            <Icon name="message-circle" :size="14" />
          </div>
          <div class="session-content">
            <div class="session-id">{{ s.sessionId.slice(0, 12) }}…</div>
            <div class="session-meta">
              <span class="meta-msgs">
                <Icon name="message-circle" :size="11" />
                {{ getSessionMessageCount(s.sessionId) }} 条
              </span>
              <span class="dot">·</span>
              <span>{{ formatTime(s.lastActiveAt) }}</span>
            </div>
          </div>
          <button
            class="session-close"
            title="关闭会话"
            @click.stop="deleteSession(s.sessionId)"
          >
            <Icon name="x" :size="14" />
          </button>
        </li>
      </ul>
    </div>

    <!-- 底部状态 -->
    <div class="sidebar-footer">
      <div :class="['status-pill', store.running ? 'busy' : 'online']">
        <span class="status-dot"></span>
        <span>{{ store.running ? '运行中' : '就绪' }}</span>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 280px;
  height: 100vh;
  background: var(--bg-elevated);
  border-right: 1px solid var(--border-subtle);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  position: relative;
  z-index: 10;
}

/* Logo */
.logo {
  padding: 20px 18px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.logo-mark {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: var(--grad-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  box-shadow: 0 4px 16px rgba(99, 102, 241, 0.4);
}
.logo-text {
  font-size: 22px;
  font-weight: 800;
  color: white;
  font-family: 'Georgia', serif;
  z-index: 1;
}
.logo-glow {
  position: absolute;
  inset: -4px;
  border-radius: 14px;
  background: var(--grad-primary);
  opacity: 0.3;
  filter: blur(8px);
  z-index: 0;
}
.logo-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--t-primary);
  margin: 0;
  letter-spacing: -0.3px;
}
.logo-sub {
  font-size: 11px;
  color: var(--t-tertiary);
  margin: 2px 0 0;
}

/* 新建会话按钮 */
.new-session-btn {
  margin: 4px 14px 16px;
  padding: 11px 16px;
  background: var(--grad-primary);
  color: white;
  border: none;
  border-radius: var(--r-md);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: all var(--dur-fast);
  box-shadow: 0 2px 8px rgba(99, 102, 241, 0.3);
}
.new-session-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 20px rgba(99, 102, 241, 0.5);
}
.new-session-btn:active { transform: translateY(0); }

/* 会话列表 */
.sessions {
  flex: 1;
  overflow-y: auto;
  padding: 0 10px;
}
.sessions-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 8px 10px;
  font-size: 11px;
  font-weight: 600;
  color: var(--t-tertiary);
  text-transform: uppercase;
  letter-spacing: 1px;
}
.sessions-count {
  background: var(--bg-surface);
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 10px;
  color: var(--t-secondary);
}
.sessions-empty {
  padding: 32px 16px;
  text-align: center;
  color: var(--t-tertiary);
  font-size: 13px;
}
.sessions-empty .hint {
  font-size: 11px;
  margin-top: 4px;
  opacity: 0.7;
}

.session-list {
  list-style: none;
  padding: 0;
  margin: 0;
}
.session-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 10px;
  margin-bottom: 4px;
  border-radius: var(--r-md);
  cursor: pointer;
  transition: all var(--dur-fast);
  border: 1px solid transparent;
  position: relative;
}
.session-item:hover {
  background: var(--bg-surface);
}
.session-item.active {
  background: var(--grad-primary-soft);
  border-color: var(--border-primary);
}
.session-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 60%;
  background: var(--grad-primary);
  border-radius: 0 3px 3px 0;
}
.session-icon {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--bg-surface);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--t-tertiary);
  flex-shrink: 0;
}
.session-item.active .session-icon {
  background: rgba(99, 102, 241, 0.2);
  color: var(--c-primary-light);
}
.session-content {
  flex: 1;
  min-width: 0;
}
.session-id {
  font-size: 13px;
  font-weight: 500;
  color: var(--t-primary);
  font-family: 'JetBrains Mono', monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.session-meta {
  font-size: 11px;
  color: var(--t-tertiary);
  margin-top: 3px;
  display: flex;
  gap: 6px;
  align-items: center;
}
.meta-msgs {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.session-meta .dot { opacity: 0.5; }
.session-close {
  width: 24px;
  height: 24px;
  border: none;
  background: transparent;
  color: var(--t-tertiary);
  cursor: pointer;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: all var(--dur-fast);
}
.session-item:hover .session-close { opacity: 1; }
.session-close:hover {
  background: var(--c-danger);
  color: white;
}

/* 底部 */
.sidebar-footer {
  padding: 14px 18px;
  border-top: 1px solid var(--border-subtle);
}
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 500;
  background: var(--bg-surface);
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.status-pill.online .status-dot {
  background: var(--c-success);
  box-shadow: 0 0 8px var(--c-success);
}
.status-pill.busy .status-dot {
  background: var(--c-warning);
  box-shadow: 0 0 8px var(--c-warning);
  animation: pulse 1.2s infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}
</style>
