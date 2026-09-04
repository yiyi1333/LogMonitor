import axios from 'axios'
import { i18n } from './i18n'

export const api = axios.create({ baseURL: '/api', withCredentials: true })

let csrfToken = ''
export async function refreshCsrf() {
  const { data } = await api.get('/auth/csrf')
  csrfToken = data.token
}
api.interceptors.request.use(async config => {
  const method = config.method?.toUpperCase()
  if (method && !['GET','HEAD','OPTIONS'].includes(method)) {
    if (!csrfToken) await refreshCsrf()
    config.headers['X-XSRF-TOKEN'] = csrfToken
  }
  return config
})
api.interceptors.response.use(response => response, error => {
  if (error.response?.status === 401 && location.pathname !== '/login') {
    const code = error.response?.data?.code
    const reason = code === 'ACCOUNT_DISABLED' ? 'account-disabled' : 'session-revoked'
    location.assign(`/login?reason=${reason}`)
  }
  return Promise.reject(error)
})

export function errorMessage(error: unknown) {
  if (axios.isAxiosError(error)) return error.response?.data?.message || error.message
  return error instanceof Error ? error.message : i18n.global.t('common.requestFailed')
}
