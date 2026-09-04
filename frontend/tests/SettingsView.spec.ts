// @vitest-environment jsdom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LlmModelDiscovery, LlmProvider, LlmSettings } from '../src/types'

const { successMock } = vi.hoisted(() => ({ successMock: vi.fn() }))
let sessionRole: 'ROOT' | 'USER' = 'USER'

vi.mock('element-plus', () => ({
  ElMessage: { success: successMock, error: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))

vi.mock('../src/api', () => ({
  api: { get: vi.fn(), put: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() },
  errorMessage: (reason: unknown) => reason instanceof Error ? reason.message : String(reason),
}))

vi.mock('../src/stores/session', () => ({
  useSessionStore: () => ({ get role() { return sessionRole } }),
}))

import { api } from '../src/api'
import SettingsView from '../src/views/SettingsView.vue'

const model = {
  id: 11,
  providerId: 3,
  providerName: 'DeepSeek',
  providerType: 'DEEPSEEK',
  modelId: 'deepseek-test',
  displayName: 'DeepSeek Test',
  enabled: true,
}
const settings: LlmSettings = {
  selectedModelId: 11,
  defaultModelId: 11,
  effectiveModel: model,
  fallbackApplied: false,
  models: [model],
}
const provider: LlmProvider = {
  id: 3,
  name: 'DeepSeek',
  providerType: 'DEEPSEEK',
  protocolType: 'OPENAI_COMPATIBLE',
  baseUrl: 'https://api.deepseek.com',
  apiKeyConfigured: true,
  enabled: true,
  createdBy: 'admin',
  createdAt: '2026-08-18T00:00:00Z',
  models: [model],
}

function mountView() {
  return mount(SettingsView, {
    global: {
      stubs: {
        EmptyState: true,
        LanguageSwitcher: true,
        ElDialog: { props: ['modelValue'], template: '<div v-if="modelValue" class="dialog-stub"><slot/><slot name="footer"/></div>' },
        ElTooltip: { template: '<span><slot /></span>' },
        ElInput: true,
        ElSelect: {
          props: ['modelValue', 'multiple', 'placeholder'],
          emits: ['update:modelValue'],
          methods: {
            update(event: Event) {
              const select = event.target as HTMLSelectElement
              const values = Array.from(select.selectedOptions).map(option => option.value)
              this.$emit('update:modelValue', this.multiple ? values : values[0])
            },
          },
          template: '<select :multiple="multiple" :aria-label="placeholder" @change="update"><slot/></select>',
        },
        ElOption: { props: ['label', 'value', 'disabled'], template: '<option :value="value" :disabled="disabled">{{ label }}</option>' },
      },
    },
  })
}

describe('SettingsView LLM settings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    sessionRole = 'USER'
    vi.mocked(api.get).mockResolvedValue({ data: settings })
    vi.mocked(api.put).mockResolvedValue({ data: settings })
  })

  it('allows a regular user to select an available model without provider management', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('DeepSeek Test')
    expect(wrapper.text()).not.toContain('供应商配置')
    await wrapper.get('.model-picker button').trigger('click')
    await flushPromises()

    expect(api.put).toHaveBeenCalledWith('/settings/llm/preference', { modelId: 11 })
    expect(successMock).toHaveBeenCalledWith('模型偏好已更新')
  })

  it('shows fallback state and root-only provider controls without exposing a key', async () => {
    sessionRole = 'ROOT'
    vi.mocked(api.get).mockImplementation(async url => {
      if (url === '/settings/llm') return { data: { ...settings, fallbackApplied: true } }
      return { data: [provider] }
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('已回退到系统默认模型')
    expect(wrapper.text()).toContain('供应商配置')
    expect(wrapper.text()).toContain('密钥已配置')
    expect(wrapper.text()).not.toContain('sk-')
  })

  it('lets a root user pull catalog models and import selected entries', async () => {
    sessionRole = 'ROOT'
    const discovery: LlmModelDiscovery = {
      source: 'CATALOG',
      catalogVersion: '2026-08-18',
      warning: '结果来自内置官方目录',
      models: [
        { modelId: 'qwen-plus', displayName: 'Qwen Plus', capabilityStatus: 'SUPPORTED', alreadyConfigured: true },
        { modelId: 'qwen3.7-plus', displayName: 'Qwen 3.7 Plus', capabilityStatus: 'SUPPORTED', alreadyConfigured: false },
      ],
    }
    vi.mocked(api.get).mockImplementation(async url => url === '/settings/llm' ? { data: settings } : { data: [provider] })
    vi.mocked(api.post).mockImplementation(async url => {
      if (url.endsWith('/model-discovery')) return { data: discovery }
      return { data: { created: [{ ...model, id: 12, modelId: 'qwen3.7-plus' }], skippedModelIds: [] } }
    })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('.discover-models').trigger('click')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/admin/llm/providers/3/model-discovery', {})
    expect(wrapper.text()).toContain('目录版本 2026-08-18')
    const modelSelect = wrapper.get<HTMLSelectElement>('[data-testid="model-discovery-select"]')
    const options = modelSelect.findAll('option')
    expect(options[0].text()).toContain('Qwen Plus (qwen-plus) · 已配置')
    expect(options[0].attributes('disabled')).toBeDefined()
    expect(options[1].text()).toContain('Qwen 3.7 Plus (qwen3.7-plus)')
    await modelSelect.setValue(['qwen3.7-plus'])
    await wrapper.get('.dialog-stub .primary-button').trigger('click')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/admin/llm/providers/3/models/import', {
      models: [{ modelId: 'qwen3.7-plus', displayName: 'Qwen 3.7 Plus' }],
    })
    expect(successMock).toHaveBeenCalledWith('已导入 1 个模型')
  })
})
