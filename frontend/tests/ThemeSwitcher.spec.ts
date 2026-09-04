// @vitest-environment jsdom

import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ThemeSwitcher from '../src/components/ThemeSwitcher.vue'
import { useThemeStore } from '../src/stores/theme'

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

describe('ThemeSwitcher', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.stubGlobal('matchMedia', vi.fn(() => ({
      matches: false,
      media: '(prefers-color-scheme: dark)',
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })))
    setActivePinia(createPinia())
    useThemeStore().initialize()
  })

  it('shows all three modes and marks the active preference', async () => {
    const wrapper = mount(ThemeSwitcher, {
      global: { stubs: { ElDropdown: DropdownStub, ElDropdownMenu: DropdownMenuStub, ElDropdownItem: DropdownItemStub } },
    })

    expect(wrapper.text()).toContain('跟随系统')
    expect(wrapper.text()).toContain('浅色模式')
    expect(wrapper.text()).toContain('深色模式')
    expect(wrapper.findAll('.selected')).toHaveLength(1)
    expect(wrapper.find('.selected').text()).toContain('跟随系统')

    wrapper.findComponent(DropdownStub).vm.$emit('command', 'dark')
    await nextTick()

    expect(useThemeStore().preference).toBe('dark')
    expect(wrapper.findAll('.selected')).toHaveLength(1)
    expect(wrapper.find('.selected').text()).toContain('深色模式')
    expect(wrapper.get('button').attributes('title')).toBe('切换主题: 深色模式')
  })
})
