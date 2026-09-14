<script setup>
import { computed, ref } from 'vue'
import AppIcon from '@/components/common/AppIcon.vue'
import MarkdownBlock from '@/components/chat/MarkdownBlock.vue'
import { formatTokens } from '@/utils/format'
import { hostOf, safeUrl } from '@/utils/preview'

const props = defineProps({
  pages: { type: Array, default: () => [] },
  /** 抓取到的正文（markdown），默认折叠，可展开查看 */
  content: { type: String, default: '' }
})

const favFailed = ref({})
const showContent = ref(false)

const list = computed(() => props.pages.filter((p) => p && p.url))

function markFavFailed(i) {
  favFailed.value = { ...favFailed.value, [i]: true }
}

/** favicon 与标题都缺失时，用域名首字母做占位 */
function initialOf(page) {
  const host = hostOf(page.url)
  const seed = page.title || host || page.url
  return String(seed).replace(/^https?:\/\//i, '').charAt(0).toUpperCase() || '?'
}
</script>

<template>
  <div class="wb">
    <article v-for="(p, i) in list" :key="i" class="wb__card" :class="{ 'is-fail': !p.success }">
      <div class="wb__fav">
        <img
          v-if="p.favicon && !favFailed[i]"
          class="wb__fav-img"
          :src="p.favicon"
          alt=""
          loading="lazy"
          referrerpolicy="no-referrer"
          @error="markFavFailed(i)"
        />
        <span v-else class="wb__fav-ph">{{ initialOf(p) }}</span>
      </div>

      <div class="wb__main">
        <a
          v-if="safeUrl(p.url)"
          class="wb__title"
          :href="safeUrl(p.url)"
          target="_blank"
          rel="noopener noreferrer"
        >{{ p.title || p.url }}</a>
        <span v-else class="wb__title wb__title--plain">{{ p.title || p.url }}</span>

        <div class="wb__url mono">{{ hostOf(p.url) || p.url }}</div>

        <p v-if="p.success" class="wb__desc">{{ p.description || '（无摘要）' }}</p>
        <p v-else class="wb__desc wb__desc--err">抓取失败：{{ p.description || '未知原因' }}</p>

        <div class="wb__foot">
          <span v-if="p.siteName" class="wb__site">{{ p.siteName }}</span>
          <span v-if="p.success">{{ formatTokens(p.contentLength) }} 字符</span>
          <a
            v-if="safeUrl(p.url)"
            class="wb__open"
            :href="safeUrl(p.url)"
            target="_blank"
            rel="noopener noreferrer"
          >
            新标签页打开 <AppIcon name="external" :size="11" />
          </a>
        </div>
      </div>
    </article>

    <div v-if="content" class="wb__more">
      <button @click="showContent = !showContent">
        {{ showContent ? '收起抓取正文' : '查看抓取正文' }}
      </button>
      <div v-if="showContent" class="wb__content">
        <MarkdownBlock :text="content" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.wb {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.wb__card {
  display: flex;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.03);
}
.wb__card:hover {
  border-color: var(--border-strong);
  background: rgba(255, 255, 255, 0.05);
}
.wb__card.is-fail {
  border-color: rgba(251, 113, 133, 0.3);
}
.wb__fav {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  flex: none;
  display: grid;
  place-items: center;
  background: rgba(255, 255, 255, 0.07);
  overflow: hidden;
}
.wb__fav-img {
  width: 18px;
  height: 18px;
  object-fit: contain;
}
.wb__fav-ph {
  font-size: 13px;
  font-weight: 600;
  color: #a5b4fc;
}
.wb__main {
  min-width: 0;
  flex: 1;
}
.wb__title {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
  text-decoration: none;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wb__title:hover {
  color: #a5b4fc;
  text-decoration: underline;
}
.wb__title--plain {
  color: var(--text-soft);
}
.wb__url {
  font-size: 10.5px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wb__desc {
  margin: 5px 0 0;
  font-size: 12.3px;
  line-height: 1.6;
  color: var(--text-soft);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.wb__desc--err {
  color: #fda4af;
}
.wb__foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
  font-size: 11px;
  color: var(--text-muted);
}
.wb__site {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 160px;
}
.wb__open {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #a5b4fc;
  text-decoration: none;
  flex: none;
}
.wb__open:hover {
  text-decoration: underline;
}
.wb__more {
  font-size: 12px;
}
.wb__more button {
  border: none;
  background: transparent;
  color: #a5b4fc;
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}
.wb__more button:hover {
  text-decoration: underline;
}
.wb__content {
  margin-top: 8px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: rgba(0, 0, 0, 0.22);
  max-height: 320px;
  overflow: auto;
  font-size: 12.5px;
}
</style>
