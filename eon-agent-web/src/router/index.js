import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'chat',
    component: () => import('@/views/ChatView.vue'),
    meta: { title: '对话', icon: 'message' }
  },
  {
    path: '/tools',
    name: 'tools',
    component: () => import('@/views/ToolsView.vue'),
    meta: { title: '能力矩阵', icon: 'plug' }
  },
  {
    path: '/api',
    name: 'api',
    component: () => import('@/views/ApiView.vue'),
    meta: { title: '接口与设置', icon: 'code' }
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
