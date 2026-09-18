<script setup>
/** 极光渐变背景：缓慢漂浮的色斑 + 细网格 + 顶部高光 */
</script>

<template>
  <div class="aurora" aria-hidden="true">
    <span class="blob blob-1" />
    <span class="blob blob-2" />
    <span class="blob blob-3" />
    <span class="blob blob-4" />
    <span class="grid" />
    <span class="vignette" />
  </div>
</template>

<style scoped>
.aurora {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  background:
    radial-gradient(120% 90% at 50% -10%, #14132a 0%, #08080f 55%, #05050b 100%);
  pointer-events: none;
}

.blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.5;
  will-change: transform;
}

.blob-1 {
  width: 46vw;
  height: 46vw;
  left: -8vw;
  top: -10vw;
  background: radial-gradient(circle at 30% 30%, #7c3aed, transparent 62%);
  animation: float 26s ease-in-out infinite;
}
.blob-2 {
  width: 40vw;
  height: 40vw;
  right: -6vw;
  top: 4vw;
  background: radial-gradient(circle at 60% 40%, #06b6d4, transparent 60%);
  animation: float 32s ease-in-out infinite reverse;
  opacity: 0.42;
}
.blob-3 {
  width: 42vw;
  height: 42vw;
  left: 22vw;
  bottom: -18vw;
  background: radial-gradient(circle at 50% 50%, #db2777, transparent 62%);
  animation: float 38s ease-in-out infinite;
  opacity: 0.32;
}
.blob-4 {
  width: 26vw;
  height: 26vw;
  right: 12vw;
  bottom: -8vw;
  background: radial-gradient(circle at 50% 50%, #4f46e5, transparent 60%);
  animation: float 30s ease-in-out infinite reverse;
  opacity: 0.34;
}

.grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.028) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.028) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(120% 80% at 50% 0%, #000 20%, transparent 78%);
  -webkit-mask-image: radial-gradient(120% 80% at 50% 0%, #000 20%, transparent 78%);
}

.vignette {
  position: absolute;
  inset: 0;
  background: radial-gradient(100% 70% at 50% 40%, transparent 40%, rgba(3, 3, 8, 0.72) 100%);
}

@keyframes float {
  0%,
  100% {
    transform: translate3d(0, 0, 0) scale(1);
  }
  33% {
    transform: translate3d(6%, -8%, 0) scale(1.12);
  }
  66% {
    transform: translate3d(-7%, 5%, 0) scale(0.94);
  }
}

@media (max-width: 900px) {
  .blob {
    filter: blur(70px);
  }
}
</style>
