import { i18n } from './i18n'

const locale = () => i18n.global.locale.value

export const formatNumber = (value: number) => new Intl.NumberFormat(locale()).format(value)

export const formatDateTime = (value?: string | Date, fallback = '') =>
  value ? new Intl.DateTimeFormat(locale(), { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value)) : fallback

export const formatChartTime = (value: string) => new Intl.DateTimeFormat(locale(), {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
}).format(new Date(value))
