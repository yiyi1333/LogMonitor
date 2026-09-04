// @vitest-environment jsdom

import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { api } from '../src/api'
import { useFilterStore } from '../src/stores/filter'
import DashboardView from '../src/views/DashboardView.vue'

vi.mock('../src/api', () => ({
  api: { get: vi.fn() },
  errorMessage: vi.fn(() => '请求失败'),
}))

const PageHeaderStub = defineComponent({
  emits: ['refresh'],
  setup(_, { emit }) {
    return () => h('button', { class: 'refresh-stub', onClick: () => emit('refresh') }, '刷新')
  },
})

const dashboard = {
  totalAccess: 0,
  averagePerMinute: 0,
  peakPerMinute: 0,
  systemErrors: 0,
  businessErrors: 0,
  accessTrend: [],
  errorTrend: [],
  topEndpoints: [],
  topErrors: [],
  services: [],
}

describe('DashboardView quick time ranges', () => {
  const now = new Date(2026, 7, 19, 11, 45, 30, 250)

  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(now)
    vi.mocked(api.get).mockReset().mockResolvedValue({ data: dashboard })
    setActivePinia(createPinia())
  })

  afterEach(() => vi.useRealTimers())

  it('renders all presets and reloads the dashboard with the selected range', async () => {
    const wrapper = mount(DashboardView, {
      global: {
        stubs: {
          PageHeader: PageHeaderStub,
          TrendChart: true,
          EmptyState: true,
          RouterLink: true,
        },
        directives: { loading: () => undefined },
      },
    })
    await flushPromises()

    const buttons = wrapper.findAll('.quick-range-options button')
    expect(buttons.map(button => button.text())).toEqual(['今天', '昨天', '7 天内', '14 天内', '30 天内'])
    expect(buttons[4].attributes('aria-pressed')).toBe('true')

    await buttons[1].trigger('click')
    await flushPromises()

    const filter = useFilterStore()
    expect(filter.rangePreset).toBe('yesterday')
    expect(buttons[1].attributes('aria-pressed')).toBe('true')
    expect(vi.mocked(api.get).mock.calls.at(-1)).toEqual([
      '/dashboard/summary',
      {
        params: expect.objectContaining({
          from: new Date(2026, 7, 18).toISOString(),
          to: new Date(2026, 7, 19).toISOString(),
        }),
      },
    ])
  })
})
