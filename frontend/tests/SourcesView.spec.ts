// @vitest-environment jsdom

import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { SourceStatus } from '../src/types'

const { confirmMock, successMock } = vi.hoisted(() => ({
  confirmMock: vi.fn(),
  successMock: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: confirmMock },
  ElMessage: { success: successMock },
}))

vi.mock('../src/api', () => ({
  api: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() },
  errorMessage: (reason: unknown) => reason instanceof Error ? reason.message : String(reason),
}))

import { api } from '../src/api'
import SourcesView from '../src/views/SourcesView.vue'

const source: SourceStatus = {
  id: 7,
  sourceName: 'account-service',
  applicationNamespace: 'account-platform',
  path: '/logs/account-service',
  include: '*.log',
  exclude: '*.error_*.log',
  status: 'ACTIVE',
  files: 2,
  bytesRead: 1024,
  totalBytes: 2048,
  parseErrors: 0,
  collectorType: 'LOCAL',
  displayAddress: 'local',
  instanceKey: 'local',
  startMode: 'HISTORY_180D',
  namespaceMigrationStatus: 'IDLE',
}

const PageHeaderStub = defineComponent({
  setup(_, { slots }) { return () => h('header', [slots.actions?.()]) },
})
const DialogStub = defineComponent({
  props: { modelValue: Boolean },
  setup(props, { slots }) { return () => props.modelValue ? h('div', { class: 'dialog-stub' }, [slots.default?.(), slots.footer?.()]) : null },
})
const InputStub = defineComponent({
  props: { modelValue: String, placeholder: String },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () => h('input', {
      value: props.modelValue,
      placeholder: props.placeholder,
      onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value),
    })
  },
})
const TooltipStub = defineComponent({
  setup(_, { slots }) { return () => h('span', slots.default?.()) },
})

function mountView() {
  return mount(SourcesView, {
    global: {
      stubs: {
        PageHeader: PageHeaderStub,
        EmptyState: true,
        DirectoryPicker: defineComponent({ props: ['modelValue'], emits: ['update:modelValue'], setup: (props, { emit }) => () => h(InputStub, { modelValue: props.modelValue, placeholder: '/data/logs/account-service', 'onUpdate:modelValue': (v: string) => emit('update:modelValue', v) }) }),
        ElDialog: DialogStub,
        ElInput: InputStub,
        ElSelect: true,
        ElOption: true,
        ElSegmented: true,
        ElTooltip: TooltipStub,
        ElProgress: true,
      },
      directives: { loading: () => undefined },
    },
  })
}

describe('SourcesView directory management', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.get).mockImplementation((url: string) => Promise.resolve({
      data: url === '/sources/options'
        ? { allowedRoots: ['/logs'], defaultInclude: '*.log', defaultExclude: '*.error_*.log' }
        : url === '/agents' ? []
        : [source],
    }))
    vi.mocked(api.post).mockResolvedValue({ data: source })
    vi.mocked(api.patch).mockResolvedValue({ data: { status: 'PENDING' } })
    vi.mocked(api.delete).mockResolvedValue({ data: undefined })
  })

  it('renders source state and creates a directory with the configured rules', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('account-service')
    expect(wrapper.text()).toContain('运行中')

    await wrapper.get('button.primary-button').trigger('click')
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('order-service')
    await inputs[1].setValue('commerce')
    await inputs[2].setValue('/logs/order-service')
    await inputs[3].setValue('order*.log')
    await inputs[4].setValue('*.error_*.log')
    await wrapper.get('.dialog-stub button.primary-button').trigger('click')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/sources/batch', {
      collectorType: 'LOCAL',
      agentId: undefined,
      sources: [{
        name: 'order-service', applicationNamespace: 'commerce', path: '/logs/order-service',
        include: 'order*.log', exclude: '*.error_*.log', startMode: 'HISTORY_180D',
      }],
    })
    expect(successMock).toHaveBeenCalledWith('已新增 1 个监控目录')
    wrapper.unmount()
  })

  it('validates form input before sending a request', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('button.primary-button').trigger('click')
    await wrapper.get('.dialog-stub button.primary-button').trigger('click')
    expect(wrapper.text()).toContain('名称须为')
    expect(api.post).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('honors delete cancellation and deletes after confirmation', async () => {
    confirmMock.mockRejectedValueOnce('cancel').mockResolvedValueOnce('confirm')
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[aria-label="删除 account-service"]').trigger('click')
    await flushPromises()
    expect(api.delete).not.toHaveBeenCalled()

    await wrapper.get('[aria-label="删除 account-service"]').trigger('click')
    await flushPromises()
    expect(api.delete).toHaveBeenCalledWith('/sources/7')
    expect(successMock).toHaveBeenCalledWith('已删除 account-service')
    wrapper.unmount()
  })
})
