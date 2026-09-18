<script setup>
import { computed, ref } from 'vue'
import AppIcon from '@/components/common/AppIcon.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { useSettingsStore } from '@/stores/settings'
import { fromNow, groupOf } from '@/utils/format'

const session = useSessionStore()
const ui = useUiStore()
const settings = useSettingsStore()

const keyword = ref('')
const pendingDelete = ref(null)

const groups = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const list = session.sessions.filter(
    (s) => !kw || (s.title || '').toLowerCase().includes(kw) || s.sessionId.toLowerCase().includes(kw)
  )
  const map = new Map()
  for (const s of list) {
    const g = groupOf(s.lastActivityAt)
    if (!map.has(g)) map.set(g, [])
    map.get(g).push(s)
  }
  return [...map.entries()].map(([name, items]) => ({ name, items }))
})

/** 该会话是否有任务在后台运行（切换走也不会中断） */
const isRunning = (id) => session.runningIds.includes(id)

async function onSelect(id) {
  if (id === session.currentId) {
    ui.closeDrawer()
    return
  }
  try {
    await session.openSession(id)
  } catch {
    /* 错误已在 store 中提示 */
  }
  ui.closeDrawer()
}

function onCreate() {
  session.newSession()
  ui.closeDrawer()
}

function confirmDelete() {
  if (pendingDelete.value) session.removeSession(pendingDelete.value)
  pendingDelete.value = null
}
</script>

<template>
  <aside class="sidebar glass">
    <!-- 品牌 -->
    <div class="brand">
      <div class="brand__mark">
        <span class="brand__mark-text">E</span>
      </div>
      <div class="brand__text">
        <h1 class="brand__name">Eon<span class="gradient-text"> Agent</span></h1>
        <p class="brand__sub">自主智能体工作台</p>
      </div>
      <button class="btn btn-ghost btn-icon sidebar__close" @click="ui.closeDrawer()">
        <AppIcon name="close" :size="17" />
      </button>
    </div>

    <!-- 新建对话 -->
    <button class="new-chat" @click="onCreate">
      <AppIcon name="plus" :size="17" />
      <span>新建对话</span>
      <span class="new-chat__hint">Ctrl/⌘ + K</span>
    </button>

    <!-- 会话列表 -->
    <div class="list-head">
      <span class="list-head__title">历史会话</span>
      <span class="list-head__count">{{ session.sessions.length }}</span>
      <button class="icon-btn" title="刷新" @click="session.loadSessions().catch(() => {})">
        <AppIcon name="refresh" :size="14" :class="{ spin: session.sessionsLoading }" />
      </button>
    </div>

    <div class="search">
      <AppIcon name="search" :size="14" />
      <input v-model="keyword" class="search__input" placeholder="搜索会话标题 / ID" />
      <button v-if="keyword" class="icon-btn" @click="keyword = ''">
        <AppIcon name="close" :size="13" />
      </button>
    </div>

    <div class="sessions">
      <div v-if="session.sessionsLoading && !session.sessions.length" class="sessions__skeleton">
        <div v-for="i in 4" :key="i" class="shimmer skeleton-item" />
      </div>

      <p v-else-if="!groups.length" class="sessions__empty">
        {{ keyword ? '没有匹配的会话' : '还没有会话，开始第一轮对话吧' }}
      </p>

      <div v-for="g in groups" :key="g.name" class="session-group">
        <p class="session-group__label">{{ g.name }}</p>
        <div
          v-for="s in g.items"
          :key="s.sessionId"
          class="session-item"
          :class="{ 'is-active': s.sessionId === session.currentId }"
          @click="onSelect(s.sessionId)"
        >
          <AppIcon name="message" :size="15" class="session-item__icon" />
          <div class="session-item__body">
            <p class="session-item__title ellipsis">{{ s.title || '未命名会话' }}</p>
            <p class="session-item__meta">
              <span v-if="isRunning(s.sessionId)" class="session-item__running">运行中</span>
              {{ s.messageCount || 0 }} 条 · {{ fromNow(s.lastActivityAt) }}
            </p>
          </div>
          <button
            class="session-item__del"
            title="删除会话"
            @click.stop="pendingDelete = s.sessionId"
          >
            <AppIcon name="trash" :size="14" />
          </button>
        </div>
      </div>
    </div>

    <!-- 底部用户：仅展示身份，不可点 -->
    <div class="sidebar__footer">
      <div class="user-chip">
        <span class="avatar"><AppIcon name="user" :size="15" /></span>
        <span class="user-chip__body">
          <span class="user-chip__id ellipsis" :title="settings.userId">{{ settings.userId }}</span>
          <span class="user-chip__role">本地用户 · 数据存于服务端</span>
        </span>
      </div>
    </div>

    <ConfirmDialog
      :open="!!pendingDelete"
      title="删除会话"
      message="删除后该会话的账本与快照将无法恢复，确认继续？"
      confirm-text="删除"
      danger
      @cancel="pendingDelete = null"
      @confirm="confirmDelete"
    />
  </aside>
</template>

<style scoped>
.sidebar {
  width: var(--sidebar-w);
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px 14px 14px;
  border-right: 1px solid var(--border);
  background: linear-gradient(180deg, rgba(16, 16, 30, 0.72), rgba(8, 8, 16, 0.6));
  overflow: hidden;
}

/* 品牌 */
.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 2px 4px;
}
.brand__mark {
  width: 38px;
  height: 38px;
  border-radius: 12px;
  background: var(--grad-brand);
  display: grid;
  place-items: center;
  box-shadow: 0 8px 22px rgba(139, 92, 246, 0.4);
  flex: none;
}
.brand__mark-text {
  font-weight: 800;
  font-size: 19px;
  color: #fff;
}
.brand__name {
  margin: 0;
  font-size: 16.5px;
  font-weight: 700;
  letter-spacing: 0.2px;
}
.brand__sub {
  margin: 1px 0 0;
  font-size: 11.5px;
  color: var(--text-muted);
}
.sidebar__close {
  display: none;
  margin-left: auto;
}

/* 新建 */
.new-chat {
  position: relative;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 11px 14px;
  border-radius: var(--r-md);
  border: 1px solid rgba(139, 92, 246, 0.35);
  background: var(--grad-soft);
  color: var(--text);
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.2s var(--ease), box-shadow 0.2s var(--ease), border-color 0.2s;
}
.new-chat:hover {
  transform: translateY(-1px);
  border-color: rgba(139, 92, 246, 0.6);
  box-shadow: 0 10px 26px rgba(139, 92, 246, 0.24);
}
.new-chat:active {
  transform: translateY(0) scale(0.99);
}
.new-chat__hint {
  margin-left: auto;
  font-size: 10.5px;
  color: var(--text-muted);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 1px 5px;
}

/* 列表头 */
.list-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 4px 0;
}
.list-head__title {
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--text-muted);
  text-transform: uppercase;
}
.list-head__count {
  font-size: 11px;
  color: var(--text-muted);
  background: rgba(255, 255, 255, 0.06);
  border-radius: 99px;
  padding: 0 6px;
}
.icon-btn {
  margin-left: auto;
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 7px;
  transition: color 0.15s, background 0.15s;
}
.icon-btn:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.08);
}
.spin {
  animation: spin 0.9s linear infinite;
}

/* 搜索 */
.search {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 10px;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: rgba(0, 0, 0, 0.22);
  color: var(--text-muted);
}
.search__input {
  flex: 1;
  min-width: 0;
  border: none;
  background: transparent;
  outline: none;
  color: var(--text);
  font-size: 12.5px;
}
.search__input::placeholder {
  color: var(--text-muted);
}

/* 会话列表 */
.sessions {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  margin: 0 -4px;
  padding: 0 4px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sessions__empty {
  color: var(--text-muted);
  font-size: 12.5px;
  text-align: center;
  padding: 22px 10px;
  margin: 0;
}
.sessions__skeleton {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.skeleton-item {
  height: 46px;
  border-radius: 12px;
}
.session-group__label {
  margin: 0 0 6px 4px;
  font-size: 11px;
  color: var(--text-muted);
  letter-spacing: 0.05em;
}
.session-item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px 10px;
  border-radius: 11px;
  cursor: pointer;
  border: 1px solid transparent;
  transition: background 0.18s var(--ease), border-color 0.18s, transform 0.18s var(--ease);
}
.session-item:hover {
  background: rgba(255, 255, 255, 0.055);
  transform: translateX(2px);
}
.session-item.is-active {
  background: linear-gradient(90deg, rgba(139, 92, 246, 0.2), rgba(34, 211, 238, 0.06));
  border-color: rgba(139, 92, 246, 0.3);
}
.session-item__icon {
  color: var(--text-muted);
  flex: none;
}
.session-item.is-active .session-item__icon {
  color: #c4b5fd;
}
.session-item__body {
  flex: 1;
  min-width: 0;
}
.session-item__title {
  margin: 0;
  font-size: 13px;
  font-weight: 500;
}
.session-item__meta {
  margin: 1px 0 0;
  font-size: 11px;
  color: var(--text-muted);
}

/* 后台仍在跑的会话：切走不会中断，这里给个可见标记 */
.session-item__running {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-right: 5px;
  padding: 0 5px;
  border-radius: 99px;
  font-size: 10px;
  color: #a5b4fc;
  background: rgba(139, 92, 246, 0.16);
  border: 1px solid rgba(139, 92, 246, 0.32);
}
.session-item__running::before {
  content: '';
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: currentColor;
  animation: pulse-dot 1.2s var(--ease) infinite;
}
@keyframes pulse-dot {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.25;
  }
}
.session-item__del {
  flex: none;
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  padding: 4px;
  border-radius: 7px;
  opacity: 0;
  transition: opacity 0.16s, color 0.16s, background 0.16s;
}
.session-item:hover .session-item__del {
  opacity: 1;
}
.session-item__del:hover {
  color: var(--err);
  background: rgba(251, 113, 133, 0.14);
}

/* 底部 */
.sidebar__footer {
  border-top: 1px solid var(--border);
  padding-top: 10px;
}
.user-chip {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px 10px;
  border-radius: 12px;
}
.avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--grad-warm);
  color: #fff;
  flex: none;
}
.user-chip__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.user-chip__id {
  font-size: 12.8px;
  font-weight: 500;
}
.user-chip__role {
  font-size: 10.5px;
  color: var(--text-muted);
}

@media (max-width: 900px) {
  .sidebar {
    width: min(86vw, 320px);
  }
  .sidebar__close {
    display: grid;
  }
  .session-item__del {
    opacity: 1;
  }
}
</style>
