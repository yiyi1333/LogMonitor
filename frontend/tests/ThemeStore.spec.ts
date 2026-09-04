// @vitest-environment jsdom

import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { THEME_STORAGE_KEY, useThemeStore } from '../src/stores/theme'

function mockSystemTheme(initialDark: boolean) {
  let matches = initialDark
  const listeners = new Set<(event: MediaQueryListEvent) => void>()
  const mediaQuery = {
    get matches() { return matches },
    media: '(prefers-color-scheme: dark)',
    onchange: null,
    addEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) => listeners.add(listener),
    removeEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) => listeners.delete(listener),
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => true,
  } as MediaQueryList
  vi.stubGlobal('matchMedia', vi.fn(() => mediaQuery))
  return (dark: boolean) => {
    matches = dark
    listeners.forEach(listener => listener({ matches: dark } as MediaQueryListEvent))
  }
}

describe('theme store', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
    document.documentElement.classList.remove('dark')
    document.documentElement.removeAttribute('style')
    setActivePinia(createPinia())
  })

  afterEach(() => vi.unstubAllGlobals())

  it('defaults to system and applies its current light theme', () => {
    mockSystemTheme(false)
    const theme = useThemeStore()
    theme.initialize()

    expect(theme.preference).toBe('system')
    expect(theme.resolvedTheme).toBe('light')
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('follows system changes only while system mode is selected', () => {
    const changeSystemTheme = mockSystemTheme(false)
    const theme = useThemeStore()
    theme.initialize()

    changeSystemTheme(true)
    expect(theme.resolvedTheme).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)

    theme.setPreference('light')
    changeSystemTheme(false)
    changeSystemTheme(true)
    expect(theme.resolvedTheme).toBe('light')
    expect(document.documentElement.dataset.theme).toBe('light')

    theme.setPreference('system')
    expect(theme.resolvedTheme).toBe('dark')
  })

  it('persists explicit choices and restores them on initialization', () => {
    mockSystemTheme(false)
    const theme = useThemeStore()
    theme.initialize()
    theme.setPreference('dark')

    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')

    setActivePinia(createPinia())
    const restored = useThemeStore()
    restored.initialize()
    expect(restored.preference).toBe('dark')
    expect(restored.resolvedTheme).toBe('dark')
  })

  it('ignores invalid stored values', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'sepia')
    mockSystemTheme(true)
    const theme = useThemeStore()
    theme.initialize()

    expect(theme.preference).toBe('system')
    expect(theme.resolvedTheme).toBe('dark')
  })
})
