/** 轻提示（Toast）状态。 */
import { defineStore } from 'pinia'
import { ref } from 'vue'

let seq = 0

export const useToastStore = defineStore('toast', () => {
  const items = ref([])

  function push(message, { type = 'info', duration = 3200 } = {}) {
    const id = ++seq
    items.value.push({ id, message, type })
    if (duration > 0) {
      setTimeout(() => remove(id), duration)
    }
    return id
  }

  function remove(id) {
    const i = items.value.findIndex((t) => t.id === id)
    if (i !== -1) items.value.splice(i, 1)
  }

  const success = (m, o) => push(m, { ...o, type: 'success' })
  const error = (m, o) => push(m, { ...o, type: 'error', duration: o?.duration ?? 5200 })
  const warn = (m, o) => push(m, { ...o, type: 'warn' })

  return { items, push, remove, success, error, warn }
})
