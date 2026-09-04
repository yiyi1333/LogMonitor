import dayjs from 'dayjs'
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api } from '../api'
import { i18n } from '../i18n'
import { isSupportedLocale, localeOption, matchSupportedLocale, type SupportedLocale } from '../i18n/locales'

export const LOCALE_STORAGE_KEY = 'log-monitor-locale'

export const useLocaleStore = defineStore('locale', () => {
  const current = ref<SupportedLocale>('zh-CN')
  const option = computed(() => localeOption(current.value))
  const elementLocale = computed(() => option.value.elementLocale)
  let initialized = false

  function apply(locale: SupportedLocale) {
    current.value = locale
    i18n.global.locale.value = locale
    dayjs.locale(localeOption(locale).dayjsLocale)
    api.defaults.headers.common['Accept-Language'] = locale
    if (typeof document !== 'undefined') document.documentElement.lang = locale
  }

  function initialize() {
    if (initialized || typeof window === 'undefined') return
    const stored = window.localStorage.getItem(LOCALE_STORAGE_KEY)
    const detected = isSupportedLocale(stored)
      ? stored
      : matchSupportedLocale(window.navigator.languages?.length ? window.navigator.languages : [window.navigator.language])
    apply(detected)
    initialized = true
  }

  function setLocale(locale: SupportedLocale) {
    apply(locale)
    if (typeof window !== 'undefined') window.localStorage.setItem(LOCALE_STORAGE_KEY, locale)
  }

  return { current, option, elementLocale, initialize, setLocale }
})
