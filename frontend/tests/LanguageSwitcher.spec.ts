// @vitest-environment jsdom

import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, nextTick } from 'vue'
import { beforeEach, describe, expect, it } from 'vitest'
import LanguageSwitcher from '../src/components/LanguageSwitcher.vue'
import { localeOptions } from '../src/i18n/locales'
import { useLocaleStore } from '../src/stores/locale'

const DropdownStub = defineComponent({
  name: 'ElDropdown',
  emits: ['command'],
  template: '<div class="dropdown-stub"><slot/><slot name="dropdown"/></div>',
})
const DropdownMenuStub = defineComponent({ name: 'ElDropdownMenu', template: '<div><slot/></div>' })
const DropdownItemStub = defineComponent({
  name: 'ElDropdownItem',
  props: ['command'],
  template: '<div class="dropdown-item-stub"><slot/></div>',
})

describe('LanguageSwitcher', () => {
  beforeEach(() => {
    localStorage.clear()
    Object.defineProperty(navigator, 'languages', { configurable: true, value: ['zh-CN'] })
    setActivePinia(createPinia())
    useLocaleStore().initialize()
  })

  it('shows all supported languages and switches without reloading', async () => {
    const wrapper = mount(LanguageSwitcher, {
      global: { stubs: { ElDropdown: DropdownStub, ElDropdownMenu: DropdownMenuStub, ElDropdownItem: DropdownItemStub } },
    })

    expect(wrapper.findAll('.dropdown-item-stub')).toHaveLength(9)
    for (const option of localeOptions) expect(wrapper.text()).toContain(option.label)
    expect(wrapper.findAll('.selected')).toHaveLength(1)

    wrapper.findComponent(DropdownStub).vm.$emit('command', 'ja-JP')
    await nextTick()

    expect(useLocaleStore().current).toBe('ja-JP')
    expect(localStorage.getItem('log-monitor-locale')).toBe('ja-JP')
    expect(document.documentElement.lang).toBe('ja-JP')
    expect(wrapper.get('button').attributes('title')).toBe('言語を切り替え')
  })
})
