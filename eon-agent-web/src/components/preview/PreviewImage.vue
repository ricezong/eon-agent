<script setup>
import { ref } from 'vue'
import AppIcon from '@/components/common/AppIcon.vue'

defineProps({
  src: { type: String, default: '' },
  name: { type: String, default: '' }
})

const failed = ref(false)
</script>

<template>
  <div class="img">
    <img
      v-if="!failed"
      class="img__el"
      :src="src"
      :alt="name"
      loading="lazy"
      referrerpolicy="no-referrer"
      @error="failed = true"
    />
    <div v-else class="img__fail">
      <AppIcon name="image" :size="18" />
      <span>图片加载失败，请下载后查看</span>
    </div>
  </div>
</template>

<style scoped>
.img {
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
  background: rgba(0, 0, 0, 0.25);
}
.img__el {
  display: block;
  max-width: 100%;
  max-height: 420px;
  margin: 0 auto;
  object-fit: contain;
}
.img__fail {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 24px;
  font-size: 12.5px;
  color: var(--text-muted);
}
</style>
