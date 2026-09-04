import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { quickRange, useFilterStore, type TimeRangePreset } from '../src/stores/filter'

describe('dashboard time range presets', () => {
  const now = new Date(2026, 7, 19, 11, 45, 30, 250)

  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(now)
    setActivePinia(createPinia())
  })

  afterEach(() => vi.useRealTimers())

  it('uses the rolling 30-day range by default', () => {
    const filter = useFilterStore()

    expect(filter.rangePreset).toBe('30d')
    expect(filter.range[0]).toEqual(new Date(now.getTime() - 30 * 86_400_000))
    expect(filter.range[1]).toEqual(now)
    expect(filter.params.from).toBe(filter.range[0].toISOString())
    expect(filter.params.to).toBe(filter.range[1].toISOString())
  })

  it('calculates today and yesterday from local calendar boundaries', () => {
    expect(quickRange('today', now)).toEqual([
      new Date(2026, 7, 19, 0, 0, 0, 0),
      now,
    ])
    expect(quickRange('yesterday', now)).toEqual([
      new Date(2026, 7, 18, 0, 0, 0, 0),
      new Date(2026, 7, 19, 0, 0, 0, 0),
    ])
  })

  it.each([
    ['7d', 7],
    ['14d', 14],
    ['30d', 30],
  ] as [TimeRangePreset, number][])('calculates the %s rolling range', (preset, days) => {
    expect(quickRange(preset, now)).toEqual([
      new Date(now.getTime() - days * 86_400_000),
      now,
    ])
  })

  it('updates query parameters and clears the selected preset for a custom range', () => {
    const filter = useFilterStore()
    filter.selectRangePreset('yesterday', now)

    expect(filter.rangePreset).toBe('yesterday')
    expect(filter.params).toMatchObject({
      from: new Date(2026, 7, 18).toISOString(),
      to: new Date(2026, 7, 19).toISOString(),
    })

    filter.clearRangePreset()
    expect(filter.rangePreset).toBeUndefined()
  })
})
