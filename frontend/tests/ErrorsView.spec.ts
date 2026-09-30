// @vitest-environment jsdom
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'
import { api } from '../src/api'
import { formatDateTime } from '../src/format'
import ErrorsView from '../src/views/ErrorsView.vue'

vi.mock('../src/api', () => ({ api: { get: vi.fn() }, errorMessage: vi.fn() }))

beforeEach(() => {
  setActivePinia(createPinia())
  vi.mocked(api.get).mockReset()
})

it('shows historical first seen between count and last seen and matches the drawer', async () => {
  const row = { id: 1, fingerprint: 'test', service: 'billing', applicationNamespace: 'billing',
    category: 'SYSTEM', summary: 'Historical error', firstSeen: '2026-01-01T01:00:00Z',
    lastSeen: '2026-09-29T01:00:00Z', occurrenceCount: 2 }
  vi.mocked(api.get).mockImplementation((url: string) => Promise.resolve({
    data: url === '/errors/groups' ? { items: [row], total: 1 } : { items: [] },
  }))
  const wrapper = mount(ErrorsView, { attachTo: document.body, global: { plugins: [ElementPlus],
    stubs: { PageHeader: true, AiAnalysisPanel: true, EmptyState: true },
  } })
  await flushPromises()
  const headers = wrapper.findAll('th').map(header => header.text())
  expect(headers).toEqual(['类型', '错误摘要', '服务', '次数', '首次发生', '最近发生', '关联接口'])
  const cells = wrapper.findAll('.el-table__body-wrapper td')
  expect(cells[4]!.text()).toBe(formatDateTime(row.firstSeen))
  expect(cells[5]!.text()).toBe(formatDateTime(row.lastSeen))
  await wrapper.find('.el-table__body-wrapper tr').trigger('click')
  await flushPromises()
  const metrics = document.querySelector('.drawer-metrics')
  expect(metrics?.textContent).toContain(formatDateTime(row.firstSeen))
  expect(metrics?.textContent).toContain(formatDateTime(row.lastSeen))
  wrapper.unmount()
})
