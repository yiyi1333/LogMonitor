<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Link2, Search, X } from 'lucide-vue-next'
import PageHeader from '../components/PageHeader.vue'
import EmptyState from '../components/EmptyState.vue'
import AiAnalysisPanel from '../components/AiAnalysisPanel.vue'
import { api, errorMessage } from '../api'
import { useFilterStore } from '../stores/filter'
import type { ErrorGroup, ErrorOccurrence, PageResult } from '../types'
import { formatDateTime } from '../format'

const filter = useFilterStore()
const { t } = useI18n()
const rows = ref<ErrorGroup[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const category = ref('')
const keyword = ref('')
const loading = ref(false)
const error = ref('')
const drawer = ref(false)
const selected = ref<ErrorGroup>()
const occurrences = ref<ErrorOccurrence[]>([])
const activeOccurrence = ref<ErrorOccurrence>()

async function load() {
  loading.value = true
  error.value = ''
  try {
    const { data } = await api.get<PageResult<ErrorGroup>>('/errors/groups', { params: { ...filter.params, category: category.value || undefined, keyword: keyword.value || undefined, page: page.value, pageSize: pageSize.value } })
    rows.value = data.items
    total.value = data.total
  } catch (reason) { error.value = errorMessage(reason) }
  finally { loading.value = false }
}

async function open(row: ErrorGroup) {
  selected.value = row
  drawer.value = true
  try {
    occurrences.value = (await api.get<PageResult<ErrorOccurrence>>(`/errors/groups/${row.id}/occurrences`, { params: { sourceId: filter.sourceId, agentId: filter.agentId } })).data.items
    activeOccurrence.value = occurrences.value[0]
  } catch (reason) { error.value = errorMessage(reason) }
}

watch(() => [filter.applicationNamespace, filter.sourceId, filter.agentId, ...filter.range], () => { page.value = 1; load() })
onMounted(load)
const fmt = (value: string) => formatDateTime(value)
</script>

<template>
  <div class="page">
    <PageHeader :title="t('errors.title')" :subtitle="t('errors.subtitle')" :loading="loading" @refresh="load"/>
    <div class="tool-row"><el-input v-model="keyword" :placeholder="t('errors.search')" clearable @keyup.enter="page=1;load()"><template #prefix><Search :size="15"/></template></el-input><el-segmented v-model="category" :options="[{label:t('common.all'),value:''},{label:t('errors.system'),value:'SYSTEM'},{label:t('errors.business'),value:'BUSINESS'}]" @change="page=1;load()"/><button class="secondary-button" @click="page=1;load()">{{ t('common.query') }}</button><span>{{ t('errors.count',{count:total}) }}</span></div>
    <div v-if="error" class="notice error">{{ error }}</div>
    <section class="data-surface" v-loading="loading"><el-table :data="rows" height="calc(100vh - 245px)" @row-click="open">
      <el-table-column :label="t('errors.type')" width="90"><template #default="scope"><span class="category-tag" :class="scope.row.category.toLowerCase()">{{ scope.row.category==='SYSTEM'?t('errors.system'):t('errors.business') }}</span></template></el-table-column>
      <el-table-column :label="t('errors.summary')" min-width="420"><template #default="scope"><div class="error-cell"><strong>{{ scope.row.summary }}</strong><span>{{ scope.row.exceptionClass||'LOG_ERROR' }}</span></div></template></el-table-column>
      <el-table-column prop="service" :label="t('errors.service')" width="120"/><el-table-column prop="occurrenceCount" :label="t('errors.occurrences')" width="90" sortable/><el-table-column :label="t('errors.firstSeen')" width="180"><template #default="scope">{{ fmt(scope.row.firstSeen) }}</template></el-table-column><el-table-column :label="t('errors.lastSeen')" width="180"><template #default="scope">{{ fmt(scope.row.lastSeen) }}</template></el-table-column>
      <el-table-column :label="t('errors.endpoint')" min-width="240"><template #default="scope"><span v-if="scope.row.inferredUri" class="inferred"><Link2 :size="13"/>{{ t('errors.inferred') }} · {{ scope.row.inferredUri }}</span><span v-else class="muted">{{ t('errors.unlinked') }}</span></template></el-table-column>
      <template #empty><EmptyState/></template></el-table><el-pagination v-model:current-page="page" :total="total" layout="total, prev, pager, next" @current-change="load"/></section>

    <el-drawer v-model="drawer" size="min(880px, 96vw)" :with-header="false"><template v-if="selected">
      <div class="drawer-header"><div><span class="category-tag" :class="selected.category.toLowerCase()">{{ selected.category==='SYSTEM'?t('errors.systemException'):t('errors.businessException') }}</span><h2>{{ selected.summary }}</h2><p>{{ selected.service }} · {{ selected.exceptionClass }}</p></div><button class="icon-button" :title="t('common.close')" @click="drawer=false"><X :size="18"/></button></div>
      <div class="drawer-metrics"><span><b>{{ selected.occurrenceCount }}</b>{{ t('errors.total') }}</span><span><b>{{ fmt(selected.firstSeen) }}</b>{{ t('errors.firstSeen') }}</span><span><b>{{ fmt(selected.lastSeen) }}</b>{{ t('errors.lastSeen') }}</span></div>
      <section class="detail-section"><div class="section-heading"><div><h2>{{ t('errors.records') }}</h2><span>{{ t('errors.recordsHint') }}</span></div></div><div class="occurrence-list"><button v-for="item in occurrences" :key="item.id" :class="{active:activeOccurrence?.id===item.id}" @click="activeOccurrence=item"><span>{{ fmt(item.occurredAt) }}</span><code>{{ item.agentName||t('errors.localNode') }} · {{ item.threadName }}</code><small>{{ item.inferredUri?t('errors.inferred'):t('errors.unlinked') }}</small></button></div><div v-if="activeOccurrence" class="occurrence-source"><span>{{ activeOccurrence.agentName||t('errors.localNode') }}</span><code>{{ activeOccurrence.sourcePath||t('errors.unknownFile') }}</code></div><pre v-if="activeOccurrence" class="stack-trace">{{ activeOccurrence.stackTrace }}</pre><EmptyState v-else/></section>
      <AiAnalysisPanel target-type="groups" :target-id="selected.id"/>
    </template></el-drawer>
  </div>
</template>
