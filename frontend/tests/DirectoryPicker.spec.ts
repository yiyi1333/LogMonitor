// @vitest-environment jsdom
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('../src/api', () => ({ api: { get: vi.fn() }, errorMessage: (e: unknown) => e instanceof Error ? e.message : String(e) }))
import { api } from '../src/api'
import DirectoryPicker from '../src/components/DirectoryPicker.vue'
const Popover = defineComponent({ props: ['visible'], setup: (p, { slots }) => () => h('div', [slots.reference?.(), p.visible ? slots.default?.() : null]) })
const Input = defineComponent({ props: ['modelValue', 'placeholder', 'disabled'], emits: ['update:modelValue'], setup: (p, { emit, slots }) => () => h('div', [h('input', { value: p.modelValue, placeholder: p.placeholder, disabled: p.disabled, onInput: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).value) }), slots.suffix?.()]) })
const roots = { path: null, parentPath: null, directories: [{ name: '/logs', path: '/logs' }], truncated: false }
function create(props = {}) { return mount(DirectoryPicker, { props: { modelValue: '', active: true, ...props }, global: { stubs: { ElPopover: Popover, ElInput: Input } } }) }
beforeEach(() => { vi.mocked(api.get).mockReset() })
afterEach(() => vi.useRealTimers())

describe('directory picker', () => {
  it('does not browse the center while a remote node is unselected', async () => {
    const view = create({ disabled: true }); await view.get('.browse-toggle').trigger('click'); expect(api.get).not.toHaveBeenCalled(); view.unmount()
  })
  it('browses roots and children and selects only on confirmation', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: roots }).mockResolvedValueOnce({ data: { path: '/logs', parentPath: null, directories: [{ name: 'app', path: '/logs/app' }], truncated: false } }).mockResolvedValueOnce({ data: { path: '/logs/app', parentPath: '/logs', directories: [], truncated: false } })
    const view = create({ agentId: 2 }); await view.get('.browse-toggle').trigger('click'); await flushPromises()
    await view.get('.directory-children button').trigger('click'); await flushPromises()
    await view.get('.directory-children button').trigger('click'); await flushPromises()
    expect(view.emitted('update:modelValue')).toBeUndefined()
    await view.get('.directory-select').trigger('click')
    expect(view.emitted('update:modelValue')).toEqual([['/logs/app']])
    expect(vi.mocked(api.get).mock.calls[2][1]?.params).toEqual({ agentId: 2, path: '/logs/app', query: undefined })
    view.unmount()
  })
  it('preserves manual input and shows errors without replacing the path', async () => {
    vi.mocked(api.get).mockRejectedValue(new Error('offline'))
    const view = create({ modelValue: '/typed' }); await view.get('input').setValue('/manual')
    await view.get('.browse-toggle').trigger('click'); await flushPromises()
    expect(view.get('[role="alert"]').text()).toBe('offline')
    expect(view.emitted('update:modelValue')).toEqual([['/manual']])
    expect(view.get('.directory-select').attributes('disabled')).toBeDefined()
    view.unmount()
  })
  it('ignores responses after switching nodes or closing the form', async () => {
    let resolve!: (value: unknown) => void
    vi.mocked(api.get).mockImplementation(() => new Promise(r => { resolve = r }) as never)
    const view = create({ agentId: 1 }); await view.get('.browse-toggle').trigger('click')
    await view.setProps({ agentId: 2 }); resolve({ data: roots }); await flushPromises()
    expect(view.find('.directory-browser').exists()).toBe(false)
    await view.get('.browse-toggle').trigger('click'); await view.setProps({ active: false }); resolve({ data: roots }); await flushPromises()
    expect(view.find('.directory-browser').exists()).toBe(false)
    expect(view.emitted('update:modelValue')).toBeUndefined()
    view.unmount()
  })
  it('debounces searches and isolates two directory rows', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    vi.mocked(api.get).mockResolvedValue({ data: roots })
    const one = create(), two = create()
    await one.get('.browse-toggle').trigger('click'); await two.get('.browse-toggle').trigger('click'); await flushPromises()
    vi.mocked(api.get).mockResolvedValue({ data: { path: '/logs', parentPath: null, directories: [], truncated: false } })
    await one.get('.directory-children button').trigger('click'); await flushPromises()
    const search = one.findAll('input')[1]
    await search.setValue('a'); await search.setValue('app'); expect(api.get).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(300); await flushPromises()
    expect(api.get).toHaveBeenCalledTimes(4)
    expect(vi.mocked(api.get).mock.calls[3][1]?.params.query).toBe('app')
    expect(two.get('.directory-current').text()).not.toBe('/logs')
    expect(two.emitted('update:modelValue')).toBeUndefined()
    one.unmount(); two.unmount()
  })
})
