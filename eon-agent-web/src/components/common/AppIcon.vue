<script setup>
import { computed } from 'vue'
import ICONS from '@/config/icons'

const props = defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 18 },
  stroke: { type: [Number, String], default: 1.8 }
})

const inner = computed(() => ICONS[props.name] || ICONS.help)
</script>

<template>
  <svg
    class="app-icon"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    :stroke-width="stroke"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    v-html="inner"
  />
</template>

<style scoped>
.app-icon {
  display: block;
  flex: none;
}
</style>
