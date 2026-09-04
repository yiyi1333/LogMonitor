// @vitest-environment jsdom

import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { api } from '../src/api'
import { LOCALE_STORAGE_KEY, useLocaleStore } from '../src/stores/locale'

function setLanguages(languages: string[]) {
  Object.defineProperty(window.navigator, 'languages', { configurable: true, value: languages })
  Object.defineProperty(window.navigator, 'language', { configurable: true, value: languages[0] || '' })
}

describe('locale store', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('matches the first supported browser language and updates global integrations', () => {
    setLanguages(['fr-CA', 'en-US'])
    const locale = useLocaleStore()
    locale.initialize()

    expect(locale.current).toBe('fr-FR')
    expect(document.documentElement.lang).toBe('fr-FR')
    expect(api.defaults.headers.common['Accept-Language']).toBe('fr-FR')
  })

  it('falls back to Simplified Chinese for unknown browser languages', () => {
    setLanguages(['ar-SA'])
    const locale = useLocaleStore()
    locale.initialize()
    expect(locale.current).toBe('zh-CN')
  })

  it('persists and restores all explicit choices', () => {
    setLanguages(['en-US'])
    const locale = useLocaleStore()
    locale.initialize()
    locale.setLocale('pt-BR')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('pt-BR')

    setActivePinia(createPinia())
    const restored = useLocaleStore()
    restored.initialize()
    expect(restored.current).toBe('pt-BR')
  })
})
