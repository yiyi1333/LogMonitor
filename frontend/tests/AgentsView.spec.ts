// @vitest-environment jsdom

import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { confirmMock, successMock } = vi.hoisted(() => ({ confirmMock: vi.fn(), successMock: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: confirmMock }, ElMessage: { success: successMock } }))
vi.mock('../src/api', () => ({
  api: { get: vi.fn(), delete: vi.fn() },
  errorMessage: (reason: unknown) => reason instanceof Error ? reason.message : String(reason),
}))
import { api } from '../src/api'
import AgentsView from '../src/views/AgentsView.vue'

const row = { id: 2, uuid: 'agent-uuid', name: 'prod-01', hostName: 'app-01', displayAddress: '10.0.0.8', version: '1.0.1', status: 'ONLINE', spoolBytes: 1024, spoolLimitBytes: 4096, lastSeenAt: '2026-08-18T01:00:00Z', allowedRoots: ['/data/logs'], createdBy: 'admin', createdAt: '2026-08-18T00:00:00Z' }
const source = (id: number, collectorType: 'LOCAL'|'AGENT', agentId?: number) => ({ id, sourceName: `source-${id}`, applicationNamespace: 'commerce', path: `/data/logs/source-${id}`, include: '*.log', exclude: '*.error_*.log', status: 'ACTIVE', files: 1, bytesRead: 10, totalBytes: 10, parseErrors: 0, collectorType, agentId, displayAddress: agentId ? '10.0.0.8' : 'local', instanceKey: agentId ? `agent-${agentId}` : 'local', startMode: 'NOW', namespaceMigrationStatus: 'IDLE' })
const sourceStatuses = [source(12, 'AGENT', 2), source(13, 'AGENT', 3), source(14, 'LOCAL')]
const HeaderStub = defineComponent({ setup(_, { slots }) { return () => h('header', slots.actions?.()) } })
const TooltipStub = defineComponent({ setup(_, { slots }) { return () => h('span', slots.default?.()) } })
const SlotStub = defineComponent({ setup(_, { slots }) { return () => h('div', [slots.title?.(), slots.default?.()]) } })

describe('AgentsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.get).mockImplementation((url: string) => Promise.resolve({ data: url === '/agents' ? [row] : sourceStatuses }))
    vi.mocked(api.delete).mockResolvedValue({ data: undefined })
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
