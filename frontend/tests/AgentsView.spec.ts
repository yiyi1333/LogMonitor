// @vitest-environment jsdom

import { defineComponent, h } from 'vue'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { confirmMock, successMock } = vi.hoisted(() => ({ confirmMock: vi.fn(), successMock: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: confirmMock }, ElMessage: { success: successMock } }))
vi.mock('../src/api', () => ({
  api: { get: vi.fn(), delete: vi.fn() },
  errorMessage: (reason: unknown) => reason instanceof Error ? reason.message : String(reason),
}))
import { api } from '../src/api'
import AgentsView from '../src/views/AgentsView.vue'

const row = { id: 2, uuid: 'agent-uuid', name: 'prod-01', hostName: 'app-01', displayAddress: '10.0.0.8', version: '1.1.1', status: 'ONLINE', spoolBytes: 1024, spoolLimitBytes: 4096, lastSeenAt: '2026-08-18T01:00:00Z', allowedRoots: ['/data/logs'], createdBy: 'admin', createdAt: '2026-08-18T00:00:00Z' }
const source = (id: number, collectorType: 'LOCAL'|'AGENT', agentId?: number) => ({ id, sourceName: `source-${id}`, applicationNamespace: 'commerce', path: `/data/logs/source-${id}`, include: '*.log', exclude: '*.error_*.log', status: 'ACTIVE', files: 1, bytesRead: 10, totalBytes: 10, parseErrors: 0, collectorType, agentId, displayAddress: agentId ? '10.0.0.8' : 'local', instanceKey: agentId ? `agent-${agentId}` : 'local', startMode: 'NOW', namespaceMigrationStatus: 'IDLE' })
const sourceStatuses = [source(12, 'AGENT', 2), source(13, 'AGENT', 3), source(14, 'LOCAL')]
const HeaderStub = defineComponent({ props: { loading: Boolean }, setup(props, { slots }) { return () => h('header', { 'data-loading': String(props.loading) }, slots.actions?.()) } })
const TooltipStub = defineComponent({ setup(_, { slots }) { return () => h('span', slots.default?.()) } })
const SlotStub = defineComponent({ setup(_, { slots }) { return () => h('div', [slots.title?.(), slots.default?.()]) } })
const render = () => mount(AgentsView, { global: { stubs: { PageHeader: HeaderStub, EmptyState: true, ElTooltip: TooltipStub, ElProgress: true, ElCollapse: SlotStub, ElCollapseItem: SlotStub }, directives: { loading: () => undefined } } })
enableAutoUnmount(afterEach)

describe('AgentsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    vi.mocked(api.get).mockImplementation((url: string) => Promise.resolve({ data: url === '/agents' ? [row] : sourceStatuses }))
    vi.mocked(api.delete).mockResolvedValue({ data: undefined })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('automatically clears recovered errors and attention counts without a loading overlay', async () => {
    vi.mocked(api.get).mockImplementation((url: string) => Promise.resolve({ data: url === '/agents' ? [{ ...row, status: 'ERROR', lastError: '/old/missing/path' }] : sourceStatuses }))
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('/old/missing/path')
    expect(wrapper.text()).toContain('1 台需关注')
    expect(wrapper.text()).toContain('0 台在线')
    vi.mocked(api.get).mockImplementation((url: string) => Promise.resolve({ data: url === '/agents' ? [{ ...row, lastError: null }] : [] }))
    vi.advanceTimersByTime(10_000)
    expect(wrapper.get('header').attributes('data-loading')).toBe('false')
    await flushPromises()
    expect(wrapper.find('.agent-row .notice.error').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('需关注')
    expect(wrapper.text()).toContain('1 台在线')
    expect(wrapper.find('.source-state.online').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('source-12')
  })

  it('pauses while hidden, refreshes on return, and cleans up on unmount', async () => {
    const wrapper = render()
    await flushPromises()
    vi.mocked(api.get).mockClear()
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(true)
    document.dispatchEvent(new Event('visibilitychange'))
    vi.advanceTimersByTime(30_000)
    expect(api.get).not.toHaveBeenCalled()
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(api.get).toHaveBeenCalledTimes(2)
    wrapper.unmount()
    vi.mocked(api.get).mockClear()
    vi.advanceTimersByTime(30_000)
    document.dispatchEvent(new Event('visibilitychange'))
    expect(api.get).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('preserves data during failures and clears the request error after recovery', async () => {
    const wrapper = render()
    await flushPromises()
    vi.mocked(api.get).mockRejectedValue(new Error('connection unavailable'))
    vi.advanceTimersByTime(10_000)
    await flushPromises()
    expect(wrapper.text()).toContain('connection unavailable')
    expect(wrapper.text()).toContain('prod-01')
    expect(wrapper.text()).toContain('source-12')
    vi.mocked(api.get).mockImplementation((url: string) => Promise.resolve({ data: url === '/agents' ? [row] : sourceStatuses }))
    vi.advanceTimersByTime(10_000)
    await flushPromises()
    expect(wrapper.text()).not.toContain('connection unavailable')
    expect(wrapper.text()).toContain('prod-01')
  })

  it('waits for both requests and prevents overlapping automatic and manual refreshes', async () => {
    const wrapper = render()
    await flushPromises()
    let resolveSources!: (value: { data: typeof sourceStatuses }) => void
    const pendingSources = new Promise<{ data: typeof sourceStatuses }>(resolve => { resolveSources = resolve })
    vi.mocked(api.get).mockClear()
    vi.mocked(api.get).mockImplementation((url: string) => url === '/agents' ? Promise.reject(new Error('temporary failure')) : pendingSources)
    vi.advanceTimersByTime(10_000)
    await flushPromises()
    vi.advanceTimersByTime(20_000)
    await wrapper.get('button.refresh').trigger('click')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(api.get).toHaveBeenCalledTimes(2)
    resolveSources({ data: sourceStatuses })
    await flushPromises()
    expect(wrapper.text()).toContain('temporary failure')
    vi.mocked(api.get).mockImplementation((url: string) => Promise.resolve({ data: url === '/agents' ? [row] : sourceStatuses }))
    await wrapper.get('button.refresh').trigger('click')
    await flushPromises()
    expect(api.get).toHaveBeenCalledTimes(4)
    expect(wrapper.text()).not.toContain('temporary failure')
  })

  it('does not leave timers behind when unmounted during the initial request', async () => {
    let resolveAgents!: (value: { data: typeof row[] }) => void
    const pendingAgents = new Promise<{ data: typeof row[] }>(resolve => { resolveAgents = resolve })
    vi.mocked(api.get).mockImplementation((url: string) => url === '/agents' ? pendingAgents : Promise.resolve({ data: sourceStatuses }))
    const wrapper = render()
    wrapper.unmount()
    resolveAgents({ data: [row] })
    await flushPromises()
    vi.mocked(api.get).mockClear()
    vi.advanceTimersByTime(30_000)
    document.dispatchEvent(new Event('visibilitychange'))
    expect(api.get).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('renders connection and queue state', async () => {
    const wrapper = mount(AgentsView, { global: { stubs: { PageHeader: HeaderStub, EmptyState: true, ElTooltip: TooltipStub, ElProgress: true, ElCollapse: SlotStub, ElCollapseItem: SlotStub }, directives: { loading: () => undefined } } })
    await flushPromises()
    expect(wrapper.text()).toContain('prod-01')
    expect(wrapper.text()).toContain('10.0.0.8')
    expect(wrapper.text()).toContain('在线')
    expect(wrapper.text()).toContain('/data/logs')
    expect(wrapper.text()).toContain('source-12')
    expect(wrapper.text()).not.toContain('source-13')
    expect(wrapper.text()).not.toContain('source-14')
    expect(api.get).toHaveBeenCalledWith('/sources/status')
  })

  it('requires confirmation before revoking an agent', async () => {
    confirmMock.mockResolvedValue('confirm')
    const wrapper = mount(AgentsView, { global: { stubs: { PageHeader: HeaderStub, EmptyState: true, ElTooltip: TooltipStub, ElProgress: true, ElCollapse: SlotStub, ElCollapseItem: SlotStub }, directives: { loading: () => undefined } } })
    await flushPromises()
    await wrapper.get('[aria-label="撤销 prod-01"]').trigger('click')
    await flushPromises()
    expect(api.delete).toHaveBeenCalledWith('/agents/2')
    expect(successMock).toHaveBeenCalledWith('已撤销 prod-01')
  })
})
