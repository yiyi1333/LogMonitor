<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Check, CirclePause, CirclePlay, CloudDownload, FlaskConical, Pencil, Plus, RefreshCw, Star, Trash2 } from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { api, errorMessage } from '../api'
import EmptyState from '../components/EmptyState.vue'
import LanguageSwitcher from '../components/LanguageSwitcher.vue'
import { useSessionStore } from '../stores/session'
import type { LlmConnectionTest, LlmModelDiscovery, LlmModelImport, LlmModelOption, LlmProvider, LlmSettings } from '../types'

const session = useSessionStore()
const { t } = useI18n()
const settings = ref<LlmSettings>({ fallbackApplied: false, models: [] })
const providers = ref<LlmProvider[]>([])
const loading = ref(false)
const saving = ref(false)
const actionKey = ref('')
const error = ref('')
const providerDialog = ref(false)
const modelDialog = ref(false)
const discoveryDialog = ref(false)
const discoveryLoading = ref(false)
const importing = ref(false)
const discovery = ref<LlmModelDiscovery>()
const discoveryError = ref('')
const selectedModelIds = ref<string[]>([])
const discoveryProviderId = ref<number>()
const editingProviderId = ref<number>()
const editingModel = ref<LlmModelOption>()
const modelProviderId = ref<number>()
const providerTypes = computed(() => [
  { value: 'DEEPSEEK', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com' },
  { value: 'OPENAI', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1' },
  { value: 'QWEN', label: t('settings.provider.qwen'), baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
  { value: 'KIMI', label: 'Kimi', baseUrl: 'https://api.moonshot.ai/v1' },
  { value: 'MINIMAX', label: 'MiniMax', baseUrl: 'https://api.minimaxi.com/v1' },
  { value: 'ZHIPU', label: t('settings.provider.zhipu'), baseUrl: 'https://open.bigmodel.cn/api/paas/v4' },
  { value: 'ANTHROPIC', label: 'Anthropic Claude', baseUrl: 'https://api.anthropic.com' },
  { value: 'GEMINI', label: 'Google Gemini', baseUrl: 'https://generativelanguage.googleapis.com/v1beta' },
  { value: 'OPENAI_COMPATIBLE', label: t('settings.provider.compatible'), baseUrl: '' },
])
const providerForm = reactive({ name: '', providerType: 'DEEPSEEK', baseUrl: 'https://api.deepseek.com', apiKey: '', enabled: true, models: [{ modelId: '', displayName: '', enabled: true }] })
const modelForm = reactive({ modelId: '', displayName: '', enabled: true })
const effectiveLabel = computed(() => settings.value.effectiveModel ? `${settings.value.effectiveModel.providerName} · ${settings.value.effectiveModel.displayName}` : t('common.notConfigured'))
const selectableDiscoveredModels = computed(() => (discovery.value?.models || []).filter(model => !model.alreadyConfigured))
const allSelectableSelected = computed(() => selectableDiscoveredModels.value.length > 0 && selectableDiscoveredModels.value.every(model => selectedModelIds.value.includes(model.modelId)))

async function load() {
  loading.value = true
  error.value = ''
  try {
    settings.value = (await api.get<LlmSettings>('/settings/llm')).data
    if (session.role === 'ROOT') providers.value = (await api.get<LlmProvider[]>('/admin/llm/providers')).data
  } catch (reason) { error.value = errorMessage(reason) }
  finally { loading.value = false }
}

async function chooseModel(modelId: number) {
  saving.value = true
  try {
    settings.value = (await api.put<LlmSettings>('/settings/llm/preference', { modelId })).data
    ElMessage.success(t('settings.preferenceUpdated'))
  } catch (reason) { error.value = errorMessage(reason) }
  finally { saving.value = false }
}

function openCreateProvider() {
  editingProviderId.value = undefined
  Object.assign(providerForm, { name: '', providerType: 'DEEPSEEK', baseUrl: 'https://api.deepseek.com', apiKey: '', enabled: true, models: [{ modelId: '', displayName: '', enabled: true }] })
  providerDialog.value = true
}

function openEditProvider(provider: LlmProvider) {
  editingProviderId.value = provider.id
  Object.assign(providerForm, { name: provider.name, providerType: provider.providerType, baseUrl: provider.baseUrl, apiKey: '', enabled: provider.enabled, models: [] })
  providerDialog.value = true
}

watch(() => providerForm.providerType, type => {
  if (editingProviderId.value) return
  providerForm.baseUrl = providerTypes.value.find(item => item.value === type)?.baseUrl || ''
})

function addInitialModel() { providerForm.models.push({ modelId: '', displayName: '', enabled: true }) }
function removeInitialModel(index: number) { if (providerForm.models.length > 1) providerForm.models.splice(index, 1) }

function showDiscovery(result: LlmModelDiscovery, providerId?: number) {
  discovery.value = result
  discoveryProviderId.value = providerId
  discoveryError.value = ''
  selectedModelIds.value = []
  discoveryDialog.value = true
}

async function discoverFromForm() {
  if (!providerForm.providerType || !providerForm.baseUrl.trim()) { error.value = t('settings.providerRequired'); return }
  if (!['QWEN', 'ZHIPU'].includes(providerForm.providerType) && !providerForm.apiKey.trim() && !editingProviderId.value) {
    error.value = t('settings.apiKeyRequired'); return
  }
  discoveryLoading.value = true
  error.value = ''
  try {
    const response = editingProviderId.value
      ? await api.post<LlmModelDiscovery>(`/admin/llm/providers/${editingProviderId.value}/model-discovery`, { baseUrl: providerForm.baseUrl.trim(), apiKey: providerForm.apiKey || undefined })
      : await api.post<LlmModelDiscovery>('/admin/llm/model-discovery', { providerType: providerForm.providerType, baseUrl: providerForm.baseUrl.trim(), apiKey: providerForm.apiKey || undefined })
    showDiscovery(response.data, editingProviderId.value)
  } catch (reason) { error.value = errorMessage(reason) }
  finally { discoveryLoading.value = false }
}

async function discoverProvider(provider: LlmProvider) {
  actionKey.value = `discovery-${provider.id}`
  error.value = ''
  try {
    const result = (await api.post<LlmModelDiscovery>(`/admin/llm/providers/${provider.id}/model-discovery`, {})).data
    showDiscovery(result, provider.id)
  } catch (reason) { error.value = errorMessage(reason) }
  finally { actionKey.value = '' }
}

function toggleDiscoveredSelection() {
  selectedModelIds.value = allSelectableSelected.value
    ? []
    : selectableDiscoveredModels.value.map(model => model.modelId)
}

async function acceptDiscoveredModels() {
  const chosen = (discovery.value?.models || []).filter(model => selectedModelIds.value.includes(model.modelId))
  if (!chosen.length) { error.value = t('settings.selectAtLeastOne'); return }
  if (!discoveryProviderId.value) {
    const existing = new Set(providerForm.models.filter(model => model.modelId.trim()).map(model => model.modelId.trim().toLowerCase()))
    const manual = providerForm.models.filter(model => model.modelId.trim())
    for (const model of chosen) {
      if (!existing.has(model.modelId.toLowerCase())) {
        manual.push({ modelId: model.modelId, displayName: model.displayName, enabled: true })
        existing.add(model.modelId.toLowerCase())
      }
    }
    providerForm.models = manual.length ? manual : [{ modelId: '', displayName: '', enabled: true }]
    discoveryDialog.value = false
    ElMessage.success(t('settings.modelsSelected',{count:chosen.length}))
    return
  }
  importing.value = true
  discoveryError.value = ''
  try {
    const result = (await api.post<LlmModelImport>(`/admin/llm/providers/${discoveryProviderId.value}/models/import`, {
      models: chosen.map(model => ({ modelId: model.modelId, displayName: model.displayName })),
    })).data
    discoveryDialog.value = false
    ElMessage.success(result.skippedModelIds.length ? t('settings.modelsImportedSkipped',{created:result.created.length,skipped:result.skippedModelIds.length}) : t('settings.modelsImported',{created:result.created.length}))
    await load()
  } catch (reason) { discoveryError.value = errorMessage(reason) }
  finally { importing.value = false }
}

async function saveProvider() {
  if (!providerForm.name.trim() || !providerForm.baseUrl.trim()) { error.value = t('settings.providerRequired'); return }
  if (!editingProviderId.value && (!providerForm.apiKey || providerForm.models.some(model => !model.modelId.trim()))) { error.value = t('settings.modelRequired'); return }
  saving.value = true
  const payload = { ...providerForm, name: providerForm.name.trim(), baseUrl: providerForm.baseUrl.trim(), models: providerForm.models.map(model => ({ ...model, modelId: model.modelId.trim(), displayName: model.displayName.trim() || model.modelId.trim() })) }
  try {
    if (editingProviderId.value) await api.put(`/admin/llm/providers/${editingProviderId.value}`, payload)
    else await api.post('/admin/llm/providers', payload)
    providerForm.apiKey = ''
    providerDialog.value = false
    ElMessage.success(editingProviderId.value ? t('settings.providerUpdated') : t('settings.providerCreated'))
    await load()
  } catch (reason) { error.value = errorMessage(reason) }
  finally { providerForm.apiKey = ''; saving.value = false }
}

async function toggleProvider(provider: LlmProvider) {
  actionKey.value = `provider-${provider.id}`
  try { await api.patch(`/admin/llm/providers/${provider.id}/status`, { enabled: !provider.enabled }); await load() }
  catch (reason) { error.value = errorMessage(reason) }
  finally { actionKey.value = '' }
}

async function removeProvider(provider: LlmProvider) {
  try { await ElMessageBox.confirm(t('settings.providerDeleteConfirm',{name:provider.name}), t('settings.providerDeleteTitle',{name:provider.name}), { confirmButtonText: t('users.permanentDelete'), cancelButtonText: t('common.cancel'), type: 'error' }) }
  catch (reason) { if (reason === 'cancel' || reason === 'close') return; error.value = errorMessage(reason); return }
  actionKey.value = `provider-${provider.id}`
  try { await api.delete(`/admin/llm/providers/${provider.id}`); ElMessage.success(t('settings.providerDeleted')); await load() }
  catch (reason) { error.value = errorMessage(reason) }
  finally { actionKey.value = '' }
}

async function testProvider(provider: LlmProvider) {
  const model = provider.models[0]
  if (!model) { error.value = t('settings.modelRequired'); return }
  actionKey.value = `provider-${provider.id}`
  try {
    const result = (await api.post<LlmConnectionTest>('/admin/llm/connection-tests', { providerId: provider.id, modelConfigId: model.id })).data
    result.success ? ElMessage.success(t('settings.connectionSuccess',{latency:result.latencyMs})) : ElMessage.error(result.message)
    await load()
  } catch (reason) { error.value = errorMessage(reason) }
  finally { actionKey.value = '' }
}

function openAddModel(provider: LlmProvider) {
  modelProviderId.value = provider.id; editingModel.value = undefined
  Object.assign(modelForm, { modelId: '', displayName: '', enabled: true }); modelDialog.value = true
}
function openEditModel(model: LlmModelOption) {
  modelProviderId.value = model.providerId; editingModel.value = model
  Object.assign(modelForm, { modelId: model.modelId, displayName: model.displayName, enabled: model.enabled }); modelDialog.value = true
}
async function saveModel() {
  if (!modelProviderId.value || !modelForm.modelId.trim()) { error.value = t('settings.modelIdRequired'); return }
  saving.value = true
  const payload = { ...modelForm, modelId: modelForm.modelId.trim(), displayName: modelForm.displayName.trim() || modelForm.modelId.trim() }
  try {
    if (editingModel.value) await api.put(`/admin/llm/providers/${modelProviderId.value}/models/${editingModel.value.id}`, payload)
    else await api.post(`/admin/llm/providers/${modelProviderId.value}/models`, payload)
    modelDialog.value = false; await load()
  } catch (reason) { error.value = errorMessage(reason) }
  finally { saving.value = false }
}
async function toggleModel(model: LlmModelOption) {
  actionKey.value = `model-${model.id}`
  try { await api.patch(`/admin/llm/providers/${model.providerId}/models/${model.id}/status`, { enabled: !model.enabled }); await load() }
  catch (reason) { error.value = errorMessage(reason) }
  finally { actionKey.value = '' }
}
async function removeModel(model: LlmModelOption) {
  try { await ElMessageBox.confirm(t('settings.modelDeleteConfirm',{name:model.displayName}), t('settings.modelDeleteTitle'), { confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel'), type: 'warning' }) }
  catch (reason) { if (reason === 'cancel' || reason === 'close') return; return }
  try { await api.delete(`/admin/llm/providers/${model.providerId}/models/${model.id}`); await load() }
  catch (reason) { error.value = errorMessage(reason) }
}
async function makeDefault(model: LlmModelOption) {
  try { await api.put('/admin/llm/default-model', { modelId: model.id }); ElMessage.success(t('settings.defaultUpdated')); await load() }
  catch (reason) { error.value = errorMessage(reason) }
}

onMounted(load)
</script>

<template>
  <div class="page settings-page">
    <header class="page-header"><div><h1>{{ t('settings.title') }}</h1><p>{{ t('settings.subtitle') }}</p></div><button class="icon-button" :title="t('settings.refresh')" :disabled="loading" @click="load"><RefreshCw :size="16" :class="{spinning:loading}"/></button></header>
    <div v-if="error" class="notice error">{{ error }}</div>
    <div v-if="settings.fallbackApplied" class="notice warning">{{ t('settings.fallback') }}</div>
    <section class="settings-section locale-settings">
      <div class="section-heading"><div><h2>{{ t('language.section') }}</h2><span>{{ t('language.sectionHint') }}</span></div></div>
      <LanguageSwitcher/>
    </section>
    <section class="settings-section">
      <div class="section-heading"><div><h2>{{ t('settings.modelPreference') }}</h2><span>{{ t('settings.effective',{model:effectiveLabel}) }}</span></div></div>
      <div v-if="settings.models.length" class="model-picker">
        <button v-for="model in settings.models" :key="model.id" :class="{active:settings.effectiveModel?.id===model.id}" :disabled="saving" @click="chooseModel(model.id)">
          <span>{{ model.providerName }}</span><strong>{{ model.displayName }}</strong><code>{{ model.modelId }}</code><Check v-if="settings.effectiveModel?.id===model.id" :size="15"/>
        </button>
      </div>
      <EmptyState v-else :title="t('settings.noModels')" :text="session.role==='ROOT'?t('settings.addProviderHint'):t('settings.contactRoot')"/>
    </section>

    <section v-if="session.role==='ROOT'" class="settings-section provider-admin">
      <div class="section-heading"><div><h2>{{ t('settings.providerConfig') }}</h2><span>{{ t('settings.secretHint') }}</span></div><button class="secondary-button" @click="openCreateProvider"><Plus :size="15"/>{{ t('settings.addProvider') }}</button></div>
      <article v-for="provider in providers" :key="provider.id" class="provider-row">
        <header><div><h3>{{ provider.name }}</h3><code>{{ provider.baseUrl }}</code></div><span class="user-state" :class="provider.enabled?'':'disabled'">{{ provider.enabled?t('common.enabled'):t('common.disabled') }}</span><div class="user-actions">
          <el-tooltip :content="t('settings.fetchModels')"><button class="row-action discover-models" :disabled="actionKey===`discovery-${provider.id}`" @click="discoverProvider(provider)"><CloudDownload :size="15"/></button></el-tooltip>
          <el-tooltip :content="t('settings.connectionTest')"><button class="row-action" :disabled="actionKey===`provider-${provider.id}`" @click="testProvider(provider)"><FlaskConical :size="15"/></button></el-tooltip>
          <el-tooltip :content="t('settings.editRotate')"><button class="row-action" @click="openEditProvider(provider)"><Pencil :size="15"/></button></el-tooltip>
          <el-tooltip :content="provider.enabled?t('common.disable'):t('common.enable')"><button class="row-action" @click="toggleProvider(provider)"><CirclePause v-if="provider.enabled" :size="15"/><CirclePlay v-else :size="15"/></button></el-tooltip>
          <el-tooltip :content="t('common.delete')"><button class="row-action danger" @click="removeProvider(provider)"><Trash2 :size="15"/></button></el-tooltip>
        </div></header>
        <div class="provider-meta"><span>{{ provider.providerType }}</span><span>{{ t('settings.keyConfigured') }}</span><span :class="{success:provider.lastTestStatus==='SUCCESS',failed:provider.lastTestStatus==='FAILED'}">{{ provider.lastTestStatus==='SUCCESS'?t('settings.connectionOk'):provider.lastTestStatus==='FAILED'?t('settings.connectionFailed'):t('settings.notTested') }}</span></div>
        <div class="provider-models">
          <div v-for="model in provider.models" :key="model.id"><span class="status-dot" :class="{inactive:!model.enabled}"></span><strong>{{ model.displayName }}</strong><code>{{ model.modelId }}</code><span v-if="settings.defaultModelId===model.id" class="default-mark"><Star :size="12"/>{{ t('common.default') }}</span><div class="user-actions">
            <el-tooltip :content="t('settings.setDefault')"><button class="row-action" :disabled="!model.enabled||settings.defaultModelId===model.id" @click="makeDefault(model)"><Star :size="14"/></button></el-tooltip>
            <el-tooltip :content="t('settings.editModel')"><button class="row-action" @click="openEditModel(model)"><Pencil :size="14"/></button></el-tooltip>
            <el-tooltip :content="model.enabled?t('settings.disableModel'):t('settings.enableModel')"><button class="row-action" @click="toggleModel(model)"><CirclePause v-if="model.enabled" :size="14"/><CirclePlay v-else :size="14"/></button></el-tooltip>
            <el-tooltip :content="t('settings.deleteModel')"><button class="row-action danger" @click="removeModel(model)"><Trash2 :size="14"/></button></el-tooltip>
          </div></div>
          <button class="add-model" @click="openAddModel(provider)"><Plus :size="14"/>{{ t('settings.addModel') }}</button>
        </div>
      </article>
      <EmptyState v-if="!providers.length" :title="t('settings.noProviders')" :text="t('settings.noProvidersHint')"/>
    </section>

    <el-dialog v-model="providerDialog" :title="editingProviderId?t('common.edit'):t('settings.addProvider')" width="min(620px, calc(100vw - 28px))" :close-on-click-modal="false">
      <form class="user-form" @submit.prevent="saveProvider">
        <label>{{ t('settings.providerName') }}<el-input v-model="providerForm.name" maxlength="80"/></label>
        <label>{{ t('settings.providerType') }}<el-select v-model="providerForm.providerType" :disabled="!!editingProviderId"><el-option v-for="type in providerTypes" :key="type.value" :label="type.label" :value="type.value"/></el-select></label>
        <label>Base URL<el-input v-model="providerForm.baseUrl"/></label>
        <label>{{ editingProviderId?t('settings.newApiKey'):'API Key' }}<el-input v-model="providerForm.apiKey" type="password" show-password autocomplete="new-password"/></label>
        <div class="form-inline-action"><button class="secondary-button discover-form-models" type="button" :disabled="discoveryLoading" @click="discoverFromForm"><CloudDownload :size="14"/>{{ discoveryLoading?t('settings.fetching'):t('settings.fetchFromProvider') }}</button><span>{{ t('settings.fetchHint') }}</span></div>
        <template v-if="!editingProviderId"><div v-for="(model,index) in providerForm.models" :key="index" class="initial-model"><el-input v-model="model.modelId" :placeholder="t('settings.modelId')"/><el-input v-model="model.displayName" :placeholder="t('settings.optionalDisplayName')"/><button class="row-action danger" type="button" :title="t('settings.remove')" @click="removeInitialModel(index)"><Trash2 :size="14"/></button></div><button class="secondary-button" type="button" @click="addInitialModel"><Plus :size="14"/>{{ t('settings.addModel') }}</button></template>
      </form>
      <template #footer><button class="secondary-button" @click="providerDialog=false">{{ t('common.cancel') }}</button><button class="primary-button compact" :disabled="saving" @click="saveProvider">{{ t('common.save') }}</button></template>
    </el-dialog>
    <el-dialog v-model="modelDialog" :title="editingModel?t('settings.editModel'):t('settings.addModel')" width="min(460px, calc(100vw - 28px))">
      <form class="user-form" @submit.prevent="saveModel"><label>{{ t('settings.modelId') }}<el-input v-model="modelForm.modelId"/></label><label>{{ t('settings.displayName') }}<el-input v-model="modelForm.displayName"/></label></form>
      <template #footer><button class="secondary-button" @click="modelDialog=false">{{ t('common.cancel') }}</button><button class="primary-button compact" :disabled="saving" @click="saveModel">{{ t('common.save') }}</button></template>
    </el-dialog>
    <el-dialog v-model="discoveryDialog" class="discovery-dialog" :title="t('settings.discoveryTitle')" width="min(680px, calc(100vw - 28px))" append-to-body :close-on-click-modal="false">
      <div v-if="discovery?.source==='CATALOG'" class="notice warning discovery-notice">{{ discovery.warning }}<span v-if="discovery.catalogVersion">{{ t('settings.catalogVersion',{version:discovery.catalogVersion}) }}</span></div>
      <div v-if="discoveryError" class="notice error discovery-notice">{{ discoveryError }}</div>
      <div v-if="discovery?.models.length" class="discovery-select-field">
        <div class="discovery-select-heading"><label>{{ t('settings.availableModels') }}</label><span>{{ t('settings.selectionCount',{total:discovery.models.length,selected:selectedModelIds.length}) }}</span></div>
        <el-select v-model="selectedModelIds" class="model-discovery-select" data-testid="model-discovery-select" multiple filterable clearable collapse-tags collapse-tags-tooltip :max-collapse-tags="3" :placeholder="t('settings.searchModels')">
          <el-option v-for="model in discovery.models" :key="model.modelId" :label="`${model.displayName} (${model.modelId})${model.alreadyConfigured?' · '+t('settings.alreadyConfigured'):''}`" :value="model.modelId" :disabled="model.alreadyConfigured">
            <div class="discovery-option"><span class="discovery-model-main"><strong>{{ model.displayName }}</strong><code>{{ model.modelId }}</code></span><span v-if="model.owner" class="discovery-owner">{{ model.owner }}</span><span class="discovery-capability" :class="model.capabilityStatus.toLowerCase()">{{ model.capabilityStatus==='SUPPORTED'?t('settings.textSupported'):t('settings.capabilityUnknown') }}</span><span v-if="model.alreadyConfigured" class="discovery-configured"><Check :size="13"/>{{ t('settings.alreadyConfigured') }}</span></div>
          </el-option>
        </el-select>
        <button class="secondary-button discovery-select-all" type="button" :disabled="!selectableDiscoveredModels.length" @click="toggleDiscoveredSelection">{{ allSelectableSelected?t('settings.clearSelection'):t('settings.selectAll') }}</button>
      </div>
      <EmptyState v-else :title="t('settings.noDiscovered')" :text="t('settings.noDiscoveredHint')"/>
      <template #footer><button class="secondary-button" @click="discoveryDialog=false">{{ t('common.cancel') }}</button><button class="primary-button compact" :disabled="importing||!selectedModelIds.length" @click="acceptDiscoveredModels">{{ discoveryProviderId?t('settings.addSelected'):t('settings.useSelected') }}</button></template>
    </el-dialog>
  </div>
</template>
