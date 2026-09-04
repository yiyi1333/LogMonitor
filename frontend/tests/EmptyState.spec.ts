// @vitest-environment jsdom

import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import EmptyState from '../src/components/EmptyState.vue'

describe('EmptyState', () => {
  it('renders the default empty result message', () => {
    const wrapper = mount(EmptyState)
    expect(wrapper.text()).toContain('暂无数据')
    expect(wrapper.text()).toContain('当前筛选范围内没有可展示的记录')
  })

  it('renders contextual copy supplied by a page', () => {
    const wrapper = mount(EmptyState, {
      props: { title: '没有错误', text: '该时间范围内未发现异常' },
    })
    expect(wrapper.text()).toContain('没有错误')
    expect(wrapper.text()).toContain('该时间范围内未发现异常')
  })
})
