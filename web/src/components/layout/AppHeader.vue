<script setup>
defineProps({
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' }
})
</script>

<template>
  <header class="header glass">
    <slot name="left" />

    <div class="header__title">
      <h2 class="header__name ellipsis">{{ title }}</h2>
      <p v-if="subtitle" class="header__sub ellipsis">{{ subtitle }}</p>
    </div>

    <div class="header__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.header {
  height: var(--header-h);
  flex: none;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 16px;
  border-bottom: 1px solid var(--border);
  background: rgba(10, 10, 20, 0.5);
  z-index: 10;
}

.header__title {
  min-width: 0;
  flex: 1;
}
.header__name {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
  line-height: 1.3;
}
.header__sub {
  margin: 0;
  font-size: 11.5px;
  color: var(--text-muted);
  line-height: 1.3;
}

.header__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
</style>
