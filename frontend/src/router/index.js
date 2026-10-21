import { createRouter, createWebHashHistory } from 'vue-router'
import Login from '../views/Login.vue'
import Layout from '../views/Layout.vue'
import Dashboard from '../views/Dashboard.vue'
import ApiKeys from '../views/ApiKeys.vue'
import Chat from '../views/Chat.vue'
import Settings from '../views/Settings.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: Login },
    {
      path: '/',
      component: Layout,
      redirect: '/dashboard',
      children: [
        { path: 'dashboard', name: 'dashboard', component: Dashboard },
        { path: 'keys', name: 'keys', component: ApiKeys },
        { path: 'chat', name: 'chat', component: Chat },
        { path: 'settings', name: 'settings', component: Settings }
      ]
    }
  ]
})

router.beforeEach((to) => {
  const token = localStorage.getItem('admin_token')
  if (to.name !== 'login' && !token) {
    return { name: 'login' }
  }
  if (to.name === 'login' && token) {
    return { name: 'dashboard' }
  }
  return true
})

export default router
