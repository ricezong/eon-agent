<script setup>
import { computed } from 'vue'
import AppHeader from '@/components/layout/AppHeader.vue'
import AppIcon from '@/components/common/AppIcon.vue'
import { TOOL_META, toolsByCategory, CATEGORY_STYLE } from '@/config/tools'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
const groups = computed(() => toolsByCategory())
const localCount = computed(() => TOOL_META.filter((t) => t.source === 'local').length)
const mcpCount = computed(() => TOOL_META.filter((t) => t.source === 'mcp').length)

/** 运行时事件流水线（与后端 EventFormatter 输出一致） */
const PIPELINE = [
  { name: 'session.start', tone: 'emerald', desc: '首帧：交付会话身份 session_id / is_new / title，前端据此落定当前会话' },
  { name: 'session.status', tone: 'violet', desc: '任务开始 / 结束（running → idle / terminated），idle 可带 stop_reason' },
  { name: 'engine.delta', tone: 'cyan', desc: '流式增量：kind=text 正文、kind=thinking 思考，前端打字机渲染' },
  { name: 'engine.thinking', tone: 'violet', desc: '完整思考块（流式关闭或回放时产出）' },
  { name: 'engine.tool_use', tone: 'amber', desc: '模型请求调用工具：name + input(JSON 字符串)' },
  { name: 'engine.tool_result', tone: 'emerald', desc: '工具执行结果：content + structured_content + success' },
  { name: 'engine.message', tone: 'cyan', desc: '本轮最终回答：content 为 [{type,text}] 数组' },
  { name: 'session.usage', tone: 'blue', desc: '本轮聚合 token 用量：prompt / completion / total' },
  { name: 'session.error', tone: 'rose', desc: '执行错误：message + error_type（前端按 type 区分处理）' },
  { name: 'user.message', tone: 'slate', desc: '仅存在于账本回放，用于还原用户侧气泡' }
]
</script>

<template>
  <section class="page">
    <AppHeader title="能力矩阵" subtitle="Agent 可自主调用的本地工具与 MCP 远程工具">
      <template #left>
        <button class="btn btn-ghost btn-icon menu-btn" title="打开侧边栏" @click="ui.toggleDrawer()">
          <AppIcon name="menu" :size="18" />
        </button>
      </template>
    </AppHeader>

    <div class="page__body">
      <!-- 概览 -->
      <div class="stats">
        <div class="stat card">
          <span class="stat__icon" style="background: linear-gradient(135deg, #7c3aed, #6366f1)">
            <AppIcon name="plug" :size="16" />
          </span>
          <div>
            <p class="stat__value">{{ TOOL_META.length }}</p>
            <p class="stat__label">已注册工具</p>
          </div>
        </div>
        <div class="stat card">
          <span class="stat__icon" style="background: linear-gradient(135deg, #0891b2, #22d3ee)">
            <AppIcon name="terminal" :size="16" />
          </span>
          <div>
            <p class="stat__value">{{ localCount }}</p>
            <p class="stat__label">本地工具</p>
          </div>
        </div>
        <div class="stat card">
          <span class="stat__icon" style="background: linear-gradient(135deg, #059669, #34d399)">
            <AppIcon name="globe" :size="16" />
          </span>
          <div>
            <p class="stat__value">{{ mcpCount }}</p>
            <p class="stat__label">MCP 远程工具</p>
          </div>
        </div>
        <div class="stat card">
          <span class="stat__icon" style="background: linear-gradient(135deg, #db2777, #f472b6)">
            <AppIcon name="layers" :size="16" />
          </span>
          <div>
            <p class="stat__value">{{ groups.length }}</p>
            <p class="stat__label">能力分类</p>
          </div>
        </div>
      </div>

      <!-- 事件流水线 -->
      <div class="panel card">
        <div class="panel__head">
          <h3 class="panel__title"><AppIcon name="activity" :size="15" /> SSE 事件流水线</h3>
          <p class="panel__desc">对话过程中后端按执行阶段推送的事件，前端逐帧驱动界面状态</p>
        </div>
        <ol class="pipeline">
          <li v-for="(p, i) in PIPELINE" :key="p.name" class="pipeline__item" :class="`tone-${p.tone}`">
            <span class="pipeline__idx">{{ String(i + 1).padStart(2, '0') }}</span>
            <span class="pipeline__name mono">{{ p.name }}</span>
            <span class="pipeline__desc">{{ p.desc }}</span>
          </li>
        </ol>
      </div>

      <!-- 工具分组 -->
      <div v-for="g in groups" :key="g.category" class="group">
        <div class="group__head">
          <span class="group__icon" :style="{ background: g.style.grad }">
            <AppIcon :name="g.style.icon" :size="15" />
          </span>
          <h3 class="group__title">{{ g.category }}</h3>
          <span class="pill">{{ g.items.length }} 个工具</span>
        </div>

        <div class="grid">
          <article v-for="t in g.items" :key="t.name" class="tool-card card">
            <header class="tool-card__head">
              <span class="tool-card__icon" :style="{ background: g.style.grad }">
                <AppIcon :name="t.icon" :size="16" />
              </span>
              <div class="tool-card__id">
                <h4 class="tool-card__label">{{ t.label }}</h4>
                <code class="tool-card__name mono">{{ t.name }}</code>
              </div>
              <span class="badge" :class="t.source === 'mcp' ? 'badge--mcp' : 'badge--local'">
                {{ t.source === 'mcp' ? 'MCP' : '本地' }}
              </span>
            </header>

            <p class="tool-card__desc">{{ t.desc }}</p>

            <div v-if="t.params?.length" class="tool-card__params">
              <span v-for="p in t.params" :key="p" class="chip mono">{{ p }}</span>
            </div>
          </article>
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
  gap: 22px;
}

/* 概览 */
.stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}
.stat {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
}
.stat__icon {
  width: 38px;
  height: 38px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  color: #fff;
  flex: none;
}
.stat__value {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  line-height: 1.1;
}
.stat__label {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--text-muted);
}

/* 面板 */
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

.pipeline {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 8px;
}
.pipeline__item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.028);
  transition: transform 0.2s var(--ease), background 0.2s;
}
.pipeline__item:hover {
  transform: translateX(3px);
  background: rgba(255, 255, 255, 0.055);
}
.pipeline__idx {
  font-size: 11px;
  font-weight: 700;
  color: var(--text-muted);
  font-family: var(--font-mono);
}
.pipeline__name {
  font-size: 12.5px;
  font-weight: 600;
  flex: none;
  min-width: 150px;
}
.pipeline__desc {
  font-size: 12.2px;
  color: var(--text-muted);
  line-height: 1.6;
}

.tone-violet .pipeline__name {
  color: #c4b5fd;
}
.tone-cyan .pipeline__name {
  color: #67e8f9;
}
.tone-amber .pipeline__name {
  color: #fcd34d;
}
.tone-emerald .pipeline__name {
  color: #6ee7b7;
}
.tone-blue .pipeline__name {
  color: #93c5fd;
}
.tone-rose .pipeline__name {
  color: #fda4af;
}
.tone-slate .pipeline__name {
  color: #cbd5e1;
}

/* 分组 */
.group__head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.group__icon {
  width: 30px;
  height: 30px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  color: #fff;
}
.group__title {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(268px, 1fr));
  gap: 12px;
}

.tool-card {
  padding: 15px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.tool-card__head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.tool-card__icon {
  width: 34px;
  height: 34px;
  border-radius: 11px;
  display: grid;
  place-items: center;
  color: #fff;
  flex: none;
}
.tool-card__id {
  min-width: 0;
  flex: 1;
}
.tool-card__label {
  margin: 0;
  font-size: 13.8px;
  font-weight: 600;
}
.tool-card__name {
  font-size: 11px;
  color: var(--text-muted);
}
.badge {
  font-size: 10.5px;
  padding: 2px 7px;
  border-radius: 99px;
  border: 1px solid var(--border);
  color: var(--text-muted);
  flex: none;
}
.badge--mcp {
  color: #6ee7b7;
  border-color: rgba(52, 211, 153, 0.3);
  background: rgba(52, 211, 153, 0.1);
}
.badge--local {
  color: #a5b4fc;
  border-color: rgba(139, 92, 246, 0.32);
  background: rgba(139, 92, 246, 0.1);
}
.tool-card__desc {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-soft);
  line-height: 1.7;
  flex: 1;
}
.tool-card__params {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}
.chip {
  font-size: 10.5px;
  padding: 2px 7px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--border);
  color: var(--text-muted);
}

@media (max-width: 640px) {
  .page__body {
    padding: 14px;
  }
  .pipeline__item {
    flex-wrap: wrap;
  }
  .pipeline__name {
    min-width: 0;
  }
}
</style>
