import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, refreshCsrf } from '../api'

export type SessionRole = 'ROOT' | 'USER'
export interface SessionUser { username: string; role: SessionRole; mustChangePassword: boolean }

export const useSessionStore = defineStore('session', () => {
  const username = ref('')
  const role = ref<SessionRole | ''>('')
  const mustChangePassword = ref(false)
  const checked = ref(false)
  function apply(user: SessionUser) {
    username.value = user.username
    role.value = user.role
    mustChangePassword.value = user.mustChangePassword
  }
  function clear() {
    username.value = ''
    role.value = ''
    mustChangePassword.value = false
  }
  async function check() {
    try { apply((await api.get<SessionUser>('/auth/me')).data) }
    catch { clear() }
    finally { checked.value = true }
    return !!username.value
  }
  async function login(user: string, password: string) {
    await refreshCsrf()
    const { data } = await api.post<SessionUser>('/auth/login', { username: user, password })
    apply(data)
    checked.value = true
    return data
  }
  async function logout() { await api.post('/auth/logout'); clear(); location.assign('/login') }
  return { username, role, mustChangePassword, checked, check, login, logout, clear }
})
