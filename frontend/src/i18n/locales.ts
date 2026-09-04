import de from 'element-plus/es/locale/lang/de'
import en from 'element-plus/es/locale/lang/en'
import es from 'element-plus/es/locale/lang/es'
import fr from 'element-plus/es/locale/lang/fr'
import ja from 'element-plus/es/locale/lang/ja'
import ko from 'element-plus/es/locale/lang/ko'
import ptBr from 'element-plus/es/locale/lang/pt-br'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import zhTw from 'element-plus/es/locale/lang/zh-tw'
import brFlag from 'flag-icons/flags/4x3/br.svg'
import cnFlag from 'flag-icons/flags/4x3/cn.svg'
import deFlag from 'flag-icons/flags/4x3/de.svg'
import esFlag from 'flag-icons/flags/4x3/es.svg'
import frFlag from 'flag-icons/flags/4x3/fr.svg'
import jpFlag from 'flag-icons/flags/4x3/jp.svg'
import krFlag from 'flag-icons/flags/4x3/kr.svg'
import twFlag from 'flag-icons/flags/4x3/tw.svg'
import usFlag from 'flag-icons/flags/4x3/us.svg'
import 'dayjs/locale/de'
import 'dayjs/locale/en'
import 'dayjs/locale/es'
import 'dayjs/locale/fr'
import 'dayjs/locale/ja'
import 'dayjs/locale/ko'
import 'dayjs/locale/pt-br'
import 'dayjs/locale/zh-cn'
import 'dayjs/locale/zh-tw'

export const supportedLocaleCodes = [
  'zh-CN', 'zh-TW', 'en-US', 'ja-JP', 'ko-KR', 'es-ES', 'fr-FR', 'de-DE', 'pt-BR',
] as const

export type SupportedLocale = typeof supportedLocaleCodes[number]

export interface LocaleOption {
  code: SupportedLocale
  label: string
  flag: string
  elementLocale: typeof en
  dayjsLocale: string
  aiLanguage: string
}

export const localeOptions: LocaleOption[] = [
  { code: 'zh-CN', label: '简体中文', flag: cnFlag, elementLocale: zhCn, dayjsLocale: 'zh-cn', aiLanguage: 'Simplified Chinese' },
  { code: 'zh-TW', label: '繁體中文', flag: twFlag, elementLocale: zhTw, dayjsLocale: 'zh-tw', aiLanguage: 'Traditional Chinese' },
  { code: 'en-US', label: 'English', flag: usFlag, elementLocale: en, dayjsLocale: 'en', aiLanguage: 'English' },
  { code: 'ja-JP', label: '日本語', flag: jpFlag, elementLocale: ja, dayjsLocale: 'ja', aiLanguage: 'Japanese' },
  { code: 'ko-KR', label: '한국어', flag: krFlag, elementLocale: ko, dayjsLocale: 'ko', aiLanguage: 'Korean' },
  { code: 'es-ES', label: 'Español', flag: esFlag, elementLocale: es, dayjsLocale: 'es', aiLanguage: 'Spanish' },
  { code: 'fr-FR', label: 'Français', flag: frFlag, elementLocale: fr, dayjsLocale: 'fr', aiLanguage: 'French' },
  { code: 'de-DE', label: 'Deutsch', flag: deFlag, elementLocale: de, dayjsLocale: 'de', aiLanguage: 'German' },
  { code: 'pt-BR', label: 'Português', flag: brFlag, elementLocale: ptBr, dayjsLocale: 'pt-br', aiLanguage: 'Brazilian Portuguese' },
]

export const localeOption = (code: SupportedLocale) =>
  localeOptions.find(option => option.code === code) || localeOptions[0]

export function matchSupportedLocale(languages: readonly string[]): SupportedLocale {
  for (const language of languages) {
    const normalized = language.replace('_', '-').toLowerCase()
    if (normalized.startsWith('zh-hant') || normalized.startsWith('zh-tw')
      || normalized.startsWith('zh-hk') || normalized.startsWith('zh-mo')) return 'zh-TW'
    if (normalized.startsWith('zh')) return 'zh-CN'
    if (normalized.startsWith('en')) return 'en-US'
    if (normalized.startsWith('ja')) return 'ja-JP'
    if (normalized.startsWith('ko')) return 'ko-KR'
    if (normalized.startsWith('es')) return 'es-ES'
    if (normalized.startsWith('fr')) return 'fr-FR'
    if (normalized.startsWith('de')) return 'de-DE'
    if (normalized.startsWith('pt')) return 'pt-BR'
  }
  return 'zh-CN'
}

export const isSupportedLocale = (value: string | null): value is SupportedLocale =>
  supportedLocaleCodes.includes(value as SupportedLocale)
