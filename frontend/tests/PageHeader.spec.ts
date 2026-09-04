// @vitest-environment jsdom

import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { api } from '../src/api'
import PageHeader from '../src/components/PageHeader.vue'
import { useFilterStore } from '../src/stores/filter'

vi.mock('../src/api', () => ({ api: { get: vi.fn() } }))

const SelectStub = defineComponent({
  setup(_, { slots }) { return () => h('div', slots.default?.()) },
})

describe('PageHeader application options', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('clears a selected namespace and instance after the source is removed', async () => {
    vi.mocked(api.get)
      .mockResolvedValueOnce({ data: [{ applicationNamespace: 'commerce', instances: [{ sourceId: 7, label: 'local：orders（/logs/orders）' }] }] })
      .mockResolvedValueOnce({ data: [] })
    const filter = useFilterStore()
    filter.applicationNamespace = 'commerce'
    filter.sourceId = 7
    const wrapper = mount(PageHeader, {
      props: { title: 'Endpoints', subtitle: 'Endpoint metrics' },
      global: {
        stubs: {
          ElSelect: SelectStub,
          ElOption: true,
          ElDatePicker: true,
          ElTooltip: true,
        },
      },
    })
    await flushPromises()

    expect(filter.applicationNamespace).toBe('commerce')
    expect(filter.sourceId).toBe(7)
    await wrapper.find('button.refresh').trigger('click')
    await flushPromises()

    expect(filter.applicationNamespace).toBe('')
    expect(filter.sourceId).toBeUndefined()
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })
})
