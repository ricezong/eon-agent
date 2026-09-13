<script setup>
import AppIcon from './AppIcon.vue'

defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '确认操作' },
  message: { type: String, default: '' },
  confirmText: { type: String, default: '确认' },
  cancelText: { type: String, default: '取消' },
  danger: { type: Boolean, default: false }
})

const emit = defineEmits(['confirm', 'cancel'])
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog">
      <div v-if="open" class="mask" @click.self="emit('cancel')">
        <div class="dialog glass-strong">
          <h3 class="dialog__title">{{ title }}</h3>
          <p v-if="message" class="dialog__msg">{{ message }}</p>
          <div class="dialog__actions">
            <button class="btn" @click="emit('cancel')">{{ cancelText }}</button>
            <button :class="['btn', danger ? 'btn-danger' : 'btn-primary']" @click="emit('confirm')">
              <AppIcon :name="danger ? 'trash' : 'check'" :size="15" />
              {{ confirmText }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 300;
  background: rgba(4, 4, 10, 0.62);
  backdrop-filter: blur(6px);
  display: grid;
  place-items: center;
  padding: 20px;
}

.dialog {
  width: min(400px, 100%);
  border-radius: var(--r-lg);
  padding: 22px;
  box-shadow: var(--shadow-lg);
}

.dialog__title {
  margin: 0 0 8px;
  font-size: 16.5px;
  font-weight: 650;
}

.dialog__msg {
  margin: 0 0 20px;
  color: var(--text-soft);
  font-size: 13.5px;
  line-height: 1.7;
}

.dialog__actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}

.dialog-enter-from,
.dialog-leave-to {
  opacity: 0;
}
.dialog-enter-from .dialog,
.dialog-leave-to .dialog {
  transform: scale(0.94) translateY(10px);
}
.dialog-enter-active,
.dialog-leave-active {
  transition: opacity 0.24s var(--ease);
}
.dialog-enter-active .dialog,
.dialog-leave-active .dialog {
  transition: transform 0.28s var(--ease);
}
</style>
