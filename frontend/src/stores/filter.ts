import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export type TimeRangePreset = 'today' | 'yesterday' | '7d' | '14d' | '30d'

const DAY_MS = 86_400_000

export function quickRange(preset: TimeRangePreset, now = new Date()): [Date, Date] {
  const end = new Date(now)
  if (preset === 'today') {
    const start = new Date(now)
    start.setHours(0, 0, 0, 0)
    return [start, end]
  }
  if (preset === 'yesterday') {
    const today = new Date(now)
    today.setHours(0, 0, 0, 0)
    const yesterday = new Date(today)
    yesterday.setDate(yesterday.getDate() - 1)
    return [yesterday, today]
  }
  const days = Number.parseInt(preset, 10)
  return [new Date(now.getTime() - days * DAY_MS), end]
}

export const useFilterStore = defineStore('filter', () => {
  const applicationNamespace = ref('')
  // Compatibility alias for views and links created before namespaces were introduced.
  const service = applicationNamespace
  const sourceId = ref<number | undefined>()
  const agentId = ref<number | undefined>()
  const rangePreset = ref<TimeRangePreset | undefined>('30d')
  const range = ref<[Date, Date]>(quickRange('30d'))
  const params = computed(() => ({
    from: range.value[0].toISOString(),
    to: range.value[1].toISOString(),
    applicationNamespace: applicationNamespace.value || undefined,
    sourceId: sourceId.value,
    agentId: agentId.value,
  }))
  function selectRangePreset(preset: TimeRangePreset, now = new Date()) {
    range.value = quickRange(preset, now)
    rangePreset.value = preset
  }
  function clearRangePreset() {
    rangePreset.value = undefined
  }
  return { service, applicationNamespace, sourceId, agentId, range, rangePreset, params, selectRangePreset, clearRangePreset }
})
