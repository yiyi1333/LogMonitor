<script setup lang="ts">
import { RefreshCw } from 'lucide-vue-next'
import { computed, onMounted, ref, useSlots, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '../api'
import { useFilterStore } from '../stores/filter'
import type { ApplicationOption } from '../types'
defineProps<{ title: string; subtitle: string; loading?: boolean }>()
const emit = defineEmits<{ refresh: [] }>()
const slots = useSlots()
const filter = useFilterStore()
const applications = ref<ApplicationOption[]>([])
const instances = computed(() => applications.value.find(item => item.applicationNamespace === filter.applicationNamespace)?.instances || [])
const { t } = useI18n()

function normalizeSelection() {
  if (filter.applicationNamespace && !applications.value.some(item => item.applicationNamespace === filter.applicationNamespace)) {
    filter.applicationNamespace = ''
    filter.sourceId = undefined
    return
  }
  if (filter.sourceId && !instances.value.some(item => item.sourceId === filter.sourceId)) filter.sourceId = undefined
}

async function loadApplications() {
  try {
    const data = (await api.get<ApplicationOption[]>('/applications/options')).data
    applications.value = Array.isArray(data) ? data.filter(item => item && Array.isArray(item.instances)) : []
    normalizeSelection()
  } catch {}
}

async function refresh() {
  await loadApplications()
  emit('refresh')
}

onMounted(async () => {
  if (slots.actions) return
  await loadApplications()
})
watch(() => filter.applicationNamespace, () => {
  if (filter.sourceId && !instances.value.some(item => item.sourceId === filter.sourceId)) filter.sourceId = undefined
})
</script>

<template>
  <header class="page-header">
    <div><h1>{{ title }}</h1><p>{{ subtitle }}</p></div>
    <slot name="actions">
    <div class="global-filters">
      <el-select v-model="filter.applicationNamespace" clearable filterable :placeholder="t('filter.allNamespaces')" :aria-label="t('filter.namespace')">
        <el-option v-for="item in applications" :key="item.applicationNamespace" :label="item.applicationNamespace" :value="item.applicationNamespace" />
      </el-select>
      <el-select v-model="filter.sourceId" clearable filterable :disabled="!filter.applicationNamespace" :placeholder="t('filter.allInstances')" :aria-label="t('filter.instance')">
        <el-option v-for="item in instances" :key="item.sourceId" :label="item.label" :value="item.sourceId">
          <el-tooltip :content="item.label" placement="right"><span class="instance-option">{{ item.label }}</span></el-tooltip>
        </el-option>
      </el-select>
      <el-date-picker v-model="filter.range" type="datetimerange" :range-separator="t('filter.to')" :start-placeholder="t('filter.start')" :end-placeholder="t('filter.end')" :clearable="false" @change="filter.clearRangePreset" />
      <button class="icon-button refresh" :title="t('filter.refresh')" :disabled="loading" @click="refresh"><RefreshCw :size="16" :class="{ spinning: loading }"/></button>
    </div>
    </slot>
  </header>
</template>
