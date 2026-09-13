/** 界面状态：侧边栏开合、移动端抽屉、确认弹窗。 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export const useUiStore = defineStore('ui', () => {
  const isMobile = ref(typeof window !== 'undefined' && window.innerWidth <= 900)
  const drawerOpen = ref(false)

  const sidebarVisible = computed(() => !isMobile.value || drawerOpen.value)

  function syncViewport() {
    if (typeof window === 'undefined') return
    isMobile.value = window.innerWidth <= 900
    if (!isMobile.value) drawerOpen.value = false
  }

  function toggleDrawer() {
    drawerOpen.value = !drawerOpen.value
  }

  function closeDrawer() {
    drawerOpen.value = false
  }

  return { isMobile, drawerOpen, sidebarVisible, syncViewport, toggleDrawer, closeDrawer }
})
