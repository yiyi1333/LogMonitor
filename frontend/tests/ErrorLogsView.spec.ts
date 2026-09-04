// @vitest-environment jsdom

import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { route, replace, push } = vi.hoisted(() => ({
  route: { query: {} as Record<string, string>, params: {} as Record<string, string> },
  replace: vi.fn().mockResolvedValue(undefined),
  push: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ replace, push }),
}))

vi.mock('../src/api', () => ({
  api: { get: vi.fn() },
  errorMessage: (reason: unknown) => reason instanceof Error ? reason.message : String(reason),
}))

import { api } from '../src/api'
import ErrorLogsView from '../src/views/ErrorLogsView.vue'

const PageHeaderStub = defineComponent({
  setup(_, { slots }) { return () => h('header', [slots.actions?.()]) },
})
const TooltipStub = defineComponent({ setup(_, { slots }) { return () => h('span', slots.default?.()) } })

function mountView() {
  return mount(ErrorLogsView, {
    global: {
      stubs: {
        PageHeader: PageHeaderStub,
        EmptyState: true,
        ElSelect: true,
        ElOption: true,
        ElSegmented: true,
        ElDatePicker: true,
        ElInput: true,
        ElTooltip: TooltipStub,
        ElTable: true,
        ElTableColumn: true,
        ElPagination: true,
      },
      directives: { loading: () => undefined },
    },
  })
}

describe('ErrorLogsView polling and URL state', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-19T12:00:00Z'))
    vi.clearAllMocks()
    route.query = { service: 'billing-service', sort: 'ASC', page: '2' }
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    vi.mocked(api.get).mockImplementation((url: string) => {
      if (url === '/applications/options') return Promise.resolve({ data: [{ applicationNamespace: 'billing-service', instances: [] }] })
      if (url === '/errors/occurrences/updates') return Promise.resolve({ data: { count: 2, latestId: 12 } })
      return Promise.resolve({ data: { items: [], total: 0, page: 2, pageSize: 50, snapshotId: 10 } })
    })
  })

  afterEach(() => vi.useRealTimers())

  it('restores query state and shows new records without jumping in ascending mode', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(api.get).toHaveBeenCalledWith('/errors/occurrences', {
      params: expect.objectContaining({ applicationNamespace: 'billing-service', sort: 'ASC', page: 2, pageSize: 50 }),
    })
    expect(replace).toHaveBeenCalledWith(expect.objectContaining({
      path: '/errors/logs', query: expect.objectContaining({ applicationNamespace: 'billing-service', sort: 'ASC', page: '2' }),
    }))

    await vi.advanceTimersByTimeAsync(10_000)
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith('/errors/occurrences/updates', {
      params: expect.objectContaining({ applicationNamespace: 'billing-service', afterId: 10 }),
    })
    expect(wrapper.text()).toContain('新增 2 条错误')
    wrapper.unmount()
  })

  it('pauses while hidden and checks immediately when visible again', async () => {
    const wrapper = mountView()
    await flushPromises()
    vi.mocked(api.get).mockClear()

    Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(20_000)
    expect(api.get).not.toHaveBeenCalledWith('/errors/occurrences/updates', expect.anything())

    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith('/errors/occurrences/updates', {
      params: expect.objectContaining({ afterId: 10 }),
    })
    wrapper.unmount()
  })

  it('clears an archived namespace from restored URL state', async () => {
    vi.mocked(api.get).mockImplementation((url: string) => {
      if (url === '/applications/options') return Promise.resolve({ data: [] })
      return Promise.resolve({ data: { items: [], total: 0, page: 1, pageSize: 50, snapshotId: 0 } })
    })

    const wrapper = mountView()
    await flushPromises()

    expect(api.get).not.toHaveBeenCalledWith('/errors/occurrences', expect.anything())
    expect(replace).toHaveBeenCalledWith(expect.objectContaining({ path: '/errors/logs' }))
    expect(replace.mock.calls[0][0].query).not.toHaveProperty('applicationNamespace')
    expect(replace.mock.calls[0][0].query).not.toHaveProperty('service')
    expect(replace.mock.calls[0][0].query).not.toHaveProperty('page')
    wrapper.unmount()
  })
})
