// @vitest-environment jsdom

import { computed, defineComponent, h, inject, provide, type InjectionKey, type Ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { UserSummary } from '../src/types'

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
import UsersView from '../src/views/UsersView.vue'

const rowsKey: InjectionKey<Ref<UserSummary[]>> = Symbol('rows')
const TableStub = defineComponent({
  props: { data: { type: Array as () => UserSummary[], default: () => [] } },
  setup(props, { slots }) {
    provide(rowsKey, computed(() => props.data))
    return () => h('div', { class: 'table-stub' }, [slots.default?.(), props.data.length ? null : slots.empty?.()])
  },
})
const ColumnStub = defineComponent({
  props: { label: String },
  setup(props, { slots }) {
    const rows = inject(rowsKey)!
    return () => h('section', { class: 'column-stub' }, [
      h('strong', props.label),
      ...rows.value.map(row => h('div', { class: 'cell-stub' }, slots.default?.({ row }))),
    ])
  },
})
const PaginationStub = defineComponent({
  props: { currentPage: Number },
  emits: ['update:currentPage', 'currentChange'],
  setup(_, { emit }) {
    return () => h('button', {
      class: 'page-two',
      onClick: () => {
        emit('update:currentPage', 2)
        emit('currentChange', 2)
      },
    }, '第 2 页')
  },
})
const DialogStub = defineComponent({
  props: { modelValue: Boolean },
  setup(props, { slots }) {
    return () => props.modelValue ? h('div', { class: 'dialog-stub' }, [slots.default?.(), slots.footer?.()]) : null
  },
})
const TooltipStub = defineComponent({
  props: { content: String, placement: String },
  setup(_, { slots }) { return () => h('span', slots.default?.()) },
})

const users: UserSummary[] = [
  { id: 1, username: 'normal.user', role: 'USER', enabled: true, mustChangePassword: false, createdAt: '2026-08-17T00:00:00Z' },
  { id: 2, username: 'pending.user', role: 'USER', enabled: true, mustChangePassword: true, createdAt: '2026-08-17T00:00:00Z' },
  { id: 3, username: 'disabled.user', role: 'USER', enabled: false, mustChangePassword: false, createdAt: '2026-08-17T00:00:00Z' },
]

function mountView() {
  return mount(UsersView, {
    global: {
      stubs: {
        ElTable: TableStub,
        ElTableColumn: ColumnStub,
        ElPagination: PaginationStub,
        ElDialog: DialogStub,
        ElTooltip: TooltipStub,
        ElInput: true,
        EmptyState: true,
      },
      directives: { loading: () => undefined },
    },
  })
}

describe('UsersView account lifecycle actions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.get).mockResolvedValue({ data: { items: users, total: users.length, page: 1, pageSize: 20 } })
    vi.mocked(api.patch).mockResolvedValue({ data: users[1] })
    vi.mocked(api.delete).mockResolvedValue({ data: undefined })
  })

  it('renders normal, first-login and disabled states with the correct actions', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('正常')
    expect(wrapper.text()).toContain('等待首次改密')
    expect(wrapper.text()).toContain('已停用')
    expect(wrapper.get('[aria-label="停用 normal.user"]').exists()).toBe(true)
    expect(wrapper.get('[aria-label="启用 disabled.user"]').exists()).toBe(true)
    expect(wrapper.get('[aria-label="删除 pending.user"]').exists()).toBe(true)
  })

  it('honors cancellation and updates status after confirmation', async () => {
    confirmMock.mockRejectedValueOnce('cancel').mockResolvedValueOnce('confirm')
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[aria-label="停用 pending.user"]').trigger('click')
    await flushPromises()
    expect(api.patch).not.toHaveBeenCalled()

    await wrapper.get('[aria-label="停用 pending.user"]').trigger('click')
    await flushPromises()
    expect(api.patch).toHaveBeenCalledWith('/users/2/status', { enabled: false })
    expect(successMock).toHaveBeenCalledWith('已停用 pending.user')
  })

  it('deletes after confirmation and returns from an empty later page', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: { items: [users[0]], total: 21, page: 2, pageSize: 20 } })
    confirmMock.mockResolvedValue('confirm')
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('.page-two').trigger('click')
    await flushPromises()
    await wrapper.get('[aria-label="删除 normal.user"]').trigger('click')
    await flushPromises()

    expect(api.delete).toHaveBeenCalledWith('/users/1')
    expect(vi.mocked(api.get).mock.calls.at(-1)?.[1]).toEqual({ params: { page: 1, pageSize: 20 } })
  })
})
