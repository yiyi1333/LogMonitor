<script setup lang="ts">
import { BrainCircuit } from 'lucide-vue-next'
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, errorMessage } from '../api'
import { localeOptions, matchSupportedLocale } from '../i18n/locales'
import { useLocaleStore } from '../stores/locale'
import type { AiAnalysis, LlmSettings } from '../types'
import EmptyState from './EmptyState.vue'

const props = defineProps<{ targetType: 'groups' | 'occurrences'; targetId: number }>()
const locale = useLocaleStore()
const { t } = useI18n()
const ai = ref<AiAnalysis>()
const histories = ref<AiAnalysis[]>([])
const settings = ref<LlmSettings>({ fallbackApplied: false, models: [] })
const loading = ref(false)
const loadError = ref('')
let timer: number | undefined

const baseUrl = computed(() => `/errors/${props.targetType}/${props.targetId}`)
const currentModelLabel = computed(() => settings.value.effectiveModel
  ? `${settings.value.effectiveModel.providerName} · ${settings.value.effectiveModel.displayName}`
  : t('common.notConfigured'))
const localeOption = (value: string) => localeOptions.find(item => item.code === matchSupportedLocale([value])) || localeOptions[0]
const localeFallback = computed(() => !!ai.value && ai.value.locale !== locale.current)
const riskLabel = (risk: string) => t(`errors.risk.${risk.toLowerCase()}`)

async function initialize() {
  clearInterval(timer)
  loadError.value = ''
  ai.value = undefined
  histories.value = []
  const [settingsResult, historyResult] = await Promise.allSettled([
    api.get<LlmSettings>('/settings/llm'),
    api.get<AiAnalysis[]>(`${baseUrl.value}/ai-analyses`),
  ])
  if (settingsResult.status === 'fulfilled') settings.value = settingsResult.value.data
  else loadError.value = errorMessage(settingsResult.reason)
  if (historyResult.status === 'fulfilled') histories.value = historyResult.value.data
  if (settings.value.effectiveModel) {
    const current = await loadCurrent()
    if (current?.status === 'RUNNING') poll()
  }
}

async function loadCurrent() {
  try {
    const result = (await api.get<AiAnalysis>(`${baseUrl.value}/ai-analysis`)).data
    ai.value = result && typeof result === 'object' ? result : undefined
    return ai.value
  } catch {
    ai.value = undefined
  }
}

async function analyze(refresh = false) {
  if (!settings.value.effectiveModel) return
  loading.value = true
  loadError.value = ''
  try {
    ai.value = (await api.post<AiAnalysis>(`${baseUrl.value}/ai-analysis`, undefined, { params: { refresh } })).data
    if (ai.value?.status === 'RUNNING') poll()
  } catch (reason) {
    loadError.value = errorMessage(reason)
  } finally {
    loading.value = false
  }
}

function poll() {
  clearInterval(timer)
  timer = window.setInterval(async () => {
    try {
      ai.value = (await api.get<AiAnalysis>(`${baseUrl.value}/ai-analysis`)).data
      if (ai.value?.status !== 'RUNNING') {
        clearInterval(timer)
        histories.value = (await api.get<AiAnalysis[]>(`${baseUrl.value}/ai-analyses`)).data
      }
    } catch {
      clearInterval(timer)
    }
  }, 1500)
}

function selectHistory(id: number) {
  ai.value = histories.value.find(item => item.id === id)
}

watch(() => [props.targetType, props.targetId], initialize, { immediate: true })
watch(() => locale.current, () => settings.value.effectiveModel && loadCurrent())
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <section class="detail-section ai-section">
    <div class="section-heading ai-heading">
      <div><h2>{{ t('errors.aiTitle') }}</h2><span>{{ currentModelLabel }} · {{ t('errors.aiPrivacy') }}</span></div>
      <div class="ai-actions">
        <el-select v-if="histories.length" :model-value="ai?.id" :placeholder="t('errors.aiHistory')" @change="selectHistory">
          <el-option v-for="item in histories" :key="item.id" :label="`${item.providerName} · ${item.modelName} · ${localeOption(item.locale).label}`" :value="item.id">
            <div class="analysis-history-option"><img :src="localeOption(item.locale).flag" alt=""/><span>{{ item.providerName }} · {{ item.modelName }}</span><small>{{ localeOption(item.locale).label }}</small></div>
          </el-option>
        </el-select>
        <button class="secondary-button" :disabled="loading||ai?.status==='RUNNING'||!settings.effectiveModel" @click="analyze(ai?.status==='SUCCESS'&&!localeFallback)">
          <BrainCircuit :size="15"/>{{ localeFallback?t('errors.generateCurrentLocale',{language:locale.option.label}):ai?.status==='SUCCESS'?t('errors.reanalyze'):ai?.status==='RUNNING'?t('errors.analyzing'):t('errors.startAnalysis') }}
        </button>
      </div>
    </div>
    <div v-if="loadError" class="notice error">{{ loadError }}</div>
    <div v-if="settings.fallbackApplied" class="notice warning">{{ t('errors.modelFallback') }}</div>
    <div v-if="localeFallback && ai" class="notice warning locale-fallback"><img :src="localeOption(ai.locale).flag" alt=""/>{{ t('errors.localeFallback',{language:localeOption(ai.locale).label,current:locale.option.label}) }}</div>
    <div v-if="ai?.status==='SUCCESS' && ai.result" class="ai-structured">
      <header><span>{{ ai.providerName }} · {{ ai.modelName }} · {{ localeOption(ai.locale).label }}</span><b :class="ai.result.riskLevel.toLowerCase()">{{ riskLabel(ai.result.riskLevel) }}</b></header>
      <article><h3>{{ t('errors.overview') }}</h3><p>{{ ai.result.overview }}</p></article>
      <article><h3>{{ t('errors.rootCauses') }}</h3><ol><li v-for="item in ai.result.rootCauses" :key="item">{{ item }}</li></ol></article>
      <article><h3>{{ t('errors.investigation') }}</h3><ol><li v-for="item in ai.result.investigationSteps" :key="item">{{ item }}</li></ol></article>
      <article><h3>{{ t('errors.fixes') }}</h3><ol><li v-for="item in ai.result.fixSuggestions" :key="item">{{ item }}</li></ol></article>
    </div>
    <div v-else-if="ai?.status==='SUCCESS'" class="ai-result">{{ ai.resultText }}</div>
    <div v-else-if="ai?.status==='FAILED'" class="notice error">{{ ai.failureReason }}</div>
    <div v-else-if="ai?.status==='RUNNING'" class="ai-running"><span class="status-dot pulse"></span>{{ t('errors.running',{provider:ai.providerName}) }}</div>
    <EmptyState v-else-if="!settings.effectiveModel" :title="t('errors.noModel')" :text="t('errors.noModelHint')"/>
    <EmptyState v-else :title="t('errors.notAnalyzed')" :text="t('errors.notAnalyzedHint')"/>
  </section>
</template>
