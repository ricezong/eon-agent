import { createRouter, createWebHashHistory } from 'vue-router'

// 只保留对话页：能力矩阵与接口文档页已移除，对应用例由后端代码与 README 承载。
// 保留 hash 路由是因为 ChatView 读 ?session= 直开指定会话。
const routes = [
  {
    path: '/',
    name: 'chat',
    component: () => import('@/views/ChatView.vue'),
    meta: { title: '对话' }
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.afterEach((to) => {
  document.title = `${to.meta.title || 'Eon'} · Eon Agent`
})

export default router
