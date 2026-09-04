// @vitest-environment jsdom

import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../src/api', () => ({
  api: { get: vi.fn(), post: vi.fn() },
  refreshCsrf: vi.fn(),
  errorMessage: (error: unknown) => String(error),
}))

import { api, refreshCsrf } from '../src/api'
import router from '../src/router'
import { useSessionStore } from '../src/stores/session'

describe('session roles and route guards', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    await router.replace('/login')
  })

  it('stores the role and password-change state returned by login', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { username: 'operator', role: 'USER', mustChangePassword: true } })
    const session = useSessionStore()

    const user = await session.login('operator', 'TempPass123')

    expect(refreshCsrf).toHaveBeenCalledOnce()
    expect(user.mustChangePassword).toBe(true)
    expect(session.username).toBe('operator')
    expect(session.role).toBe('USER')
    expect(session.mustChangePassword).toBe(true)
  })

  it('allows root users, rejects regular users, and forces temporary-password users to change passwords', async () => {
    const session = useSessionStore()
    session.checked = true
    session.username = 'rootadmin'
    session.role = 'ROOT'
    session.mustChangePassword = false

    await router.push('/users')
    expect(router.currentRoute.value.path).toBe('/users')

    session.role = 'USER'
    await router.push('/dashboard')
    await router.push('/users')
    expect(router.currentRoute.value.path).toBe('/dashboard')

    session.mustChangePassword = true
    await router.push('/endpoints')
    expect(router.currentRoute.value.path).toBe('/change-password')
  })

  it('keeps the error stream and occurrence detail as authenticated nested routes', async () => {
    const session = useSessionStore()
    session.checked = true
    session.username = 'operator'
    session.role = 'USER'
    session.mustChangePassword = false

    await router.push('/errors/logs?service=billing-service&sort=ASC')
    expect(router.currentRoute.value.path).toBe('/errors/logs')
    expect(router.currentRoute.value.query.service).toBe('billing-service')
    await router.push('/errors/logs/42?service=billing-service')
    expect(router.currentRoute.value.path).toBe('/errors/logs/42')
  })
})
