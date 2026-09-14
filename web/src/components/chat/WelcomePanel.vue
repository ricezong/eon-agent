<script setup>
import AppIcon from '@/components/common/AppIcon.vue'

const emit = defineEmits(['pick'])

const CAPS = [
  { icon: 'globe', title: '实时联网', desc: '搜索与抓取网页，获取最新信息', tone: 'from-cyan' },
  { icon: 'fileText', title: '文件处理', desc: '读写文件、浏览目录、下载资源', tone: 'from-violet' },
  { icon: 'book', title: '小说漫画', desc: '搜索作品、拉取章节、批量导出', tone: 'from-emerald' },
  { icon: 'listChecks', title: '任务规划', desc: '自动拆解复杂任务并跟踪进度', tone: 'from-pink' }
]

const EXAMPLES = [
  '帮我搜索最近的 AI 新闻，总结三条重点',
  '读取 workspace 目录下的文件并做个概览',
  '制定一份 Vue3 前端学习路线（含待办清单）',
  '搜索小说《镇魂街》并下载前两章'
]
</script>

<template>
  <div class="welcome">
    <div class="welcome__badge">
      <AppIcon name="sparkles" :size="13" />
      <span>由 LangChain4j + Spring Boot 驱动</span>
    </div>

    <h2 class="welcome__title">
      你好，我是 <span class="gradient-text">Eon</span>
    </h2>
    <p class="welcome__sub">
      一个能自主思考、调用工具、执行多步任务的智能体。告诉我你想做什么。
    </p>

    <div class="welcome__caps">
      <div v-for="c in CAPS" :key="c.title" class="cap" :class="c.tone">
        <span class="cap__icon"><AppIcon :name="c.icon" :size="17" /></span>
        <div>
          <p class="cap__title">{{ c.title }}</p>
          <p class="cap__desc">{{ c.desc }}</p>
        </div>
      </div>
    </div>

    <div class="welcome__examples">
      <button
        v-for="e in EXAMPLES"
        :key="e"
        class="example"
        @click="emit('pick', e)"
      >
        <AppIcon name="arrowRight" :size="13" />
        <span>{{ e }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.welcome {
  max-width: 860px;
  margin: 0 auto;
  padding: 34px 20px 20px;
  text-align: center;
  animation: fadeUp 0.5s var(--ease) both;
}

.welcome__badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border-radius: 99px;
  border: 1px solid rgba(139, 92, 246, 0.28);
  background: rgba(139, 92, 246, 0.1);
  color: #c4b5fd;
  font-size: 11.8px;
  margin-bottom: 18px;
}

.welcome__title {
  margin: 0 0 10px;
  font-size: clamp(28px, 5vw, 42px);
  font-weight: 750;
  letter-spacing: -0.5px;
}

.welcome__sub {
  margin: 0 auto 26px;
  max-width: 560px;
  color: var(--text-soft);
  font-size: 14.5px;
  line-height: 1.8;
}

.welcome__caps {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
  text-align: left;
  margin-bottom: 26px;
}

.cap {
  display: flex;
  gap: 11px;
  padding: 14px;
  border-radius: var(--r-md);
  border: 1px solid var(--border);
  background: var(--panel);
  backdrop-filter: blur(18px);
  transition: transform 0.24s var(--ease), border-color 0.24s, box-shadow 0.24s;
}
.cap:hover {
  transform: translateY(-3px);
  border-color: var(--border-strong);
  box-shadow: var(--shadow-md);
}
.cap__icon {
  width: 36px;
  height: 36px;
  border-radius: 11px;
  display: grid;
  place-items: center;
  flex: none;
  color: #fff;
}
.from-cyan .cap__icon {
  background: linear-gradient(135deg, #0891b2, #22d3ee);
}
.from-violet .cap__icon {
  background: linear-gradient(135deg, #7c3aed, #6366f1);
}
.from-emerald .cap__icon {
  background: linear-gradient(135deg, #059669, #34d399);
}
.from-pink .cap__icon {
  background: linear-gradient(135deg, #db2777, #f472b6);
}
.cap__title {
  margin: 0 0 2px;
  font-size: 13.5px;
  font-weight: 600;
}
.cap__desc {
  margin: 0;
  font-size: 12.2px;
  color: var(--text-muted);
  line-height: 1.6;
}

.welcome__examples {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
  justify-content: center;
}

.example {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 9px 14px;
  border-radius: 99px;
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.035);
  color: var(--text-soft);
  font-size: 12.8px;
  cursor: pointer;
  transition: all 0.22s var(--ease);
}
.example:hover {
  color: #fff;
  border-color: rgba(139, 92, 246, 0.5);
  background: rgba(139, 92, 246, 0.14);
  transform: translateY(-2px);
}

@media (max-width: 640px) {
  .welcome {
    padding: 20px 14px 10px;
  }
  .welcome__caps {
    grid-template-columns: 1fr;
  }
}
</style>
