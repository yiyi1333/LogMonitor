import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from './components/AppLayout.vue'
import { useSessionStore } from './stores/session'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
    { path: '/change-password', component: () => import('./views/ChangePasswordView.vue') },
    { path: '/', component: AppLayout, children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', component: () => import('./views/DashboardView.vue') },
      { path: 'endpoints', component: () => import('./views/EndpointsView.vue') },
      { path: 'errors', component: () => import('./views/ErrorsLayout.vue'), children: [
        { path: '', redirect: '/errors/groups' },
        { path: 'groups', component: () => import('./views/ErrorsView.vue') },
        { path: 'logs', component: () => import('./views/ErrorLogsView.vue') },
        { path: 'logs/:occurrenceId', component: () => import('./views/ErrorOccurrenceDetailView.vue') },
      ]},
      { path: 'sources', component: () => import('./views/SourcesView.vue') },
      { path: 'agents', component: () => import('./views/AgentsView.vue') },
      { path: 'settings', component: () => import('./views/SettingsView.vue') },
      { path: 'users', component: () => import('./views/UsersView.vue'), meta: { rootOnly: true } },
    ]},
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})

router.beforeEach(async to => {
  if (to.meta.public) return true
  const session = useSessionStore()
  if (!session.checked) await session.check()
  if (!session.username) return { path: '/login', query: { redirect: to.fullPath } }
  if (session.mustChangePassword && to.path !== '/change-password') return { path: '/change-password' }
  if (to.meta.rootOnly && session.role !== 'ROOT') return { path: '/dashboard' }
  return true
})
export default router
