import { createRouter, createWebHashHistory } from 'vue-router'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/dashboard', name: 'Dashboard', component: () => import('../views/Dashboard.vue') },
    { path: '/games', name: 'Games', component: () => import('../views/GameManage.vue') },
    { path: '/events', name: 'Events', component: () => import('../views/EventConfig.vue') },
    { path: '/attribution', name: 'Attribution', component: () => import('../views/AttributionData.vue') },
  ]
})

export default router
