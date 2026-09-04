import { computed, onScopeDispose, ref } from 'vue'
import { defineStore } from 'pinia'

export type ThemePreference = 'system' | 'light' | 'dark'
export type ResolvedTheme = 'light' | 'dark'

export const THEME_STORAGE_KEY = 'log-monitor-theme'

const isThemePreference = (value: string | null): value is ThemePreference =>
  value === 'system' || value === 'light' || value === 'dark'

export const useThemeStore = defineStore('theme', () => {
  const preference = ref<ThemePreference>('system')
  const systemTheme = ref<ResolvedTheme>('light')
  const resolvedTheme = computed<ResolvedTheme>(() =>
    preference.value === 'system' ? systemTheme.value : preference.value,
  )
  let mediaQuery: MediaQueryList | undefined
  let initialized = false

  function applyTheme() {
    if (typeof document === 'undefined') return
    const root = document.documentElement
    root.dataset.theme = resolvedTheme.value
    root.classList.toggle('dark', resolvedTheme.value === 'dark')
    root.style.colorScheme = resolvedTheme.value
    document.querySelector('meta[name="theme-color"]')?.setAttribute(
      'content', resolvedTheme.value === 'dark' ? '#111311' : '#f4f6f3',
    )
  }

  function handleSystemTheme(event: MediaQueryListEvent | MediaQueryList) {
    systemTheme.value = event.matches ? 'dark' : 'light'
    if (preference.value === 'system') applyTheme()
  }

  function initialize() {
    if (initialized || typeof window === 'undefined') return
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY)
    preference.value = isThemePreference(stored) ? stored : 'system'
    mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
    handleSystemTheme(mediaQuery)
    mediaQuery.addEventListener('change', handleSystemTheme)
    initialized = true
    applyTheme()
  }

  function setPreference(value: ThemePreference) {
    preference.value = value
    if (typeof window !== 'undefined') window.localStorage.setItem(THEME_STORAGE_KEY, value)
    applyTheme()
  }

  onScopeDispose(() => mediaQuery?.removeEventListener('change', handleSystemTheme))

  return { preference, resolvedTheme, initialize, setPreference }
})
