<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowDownUp, Pause, Play, RefreshCw, Search } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import EmptyState from '../components/EmptyState.vue'
import { api, errorMessage } from '../api'
import { formatDateTime } from '../format'
import type { ApplicationOption, ErrorLogItem, ErrorOccurrencePage, ErrorOccurrenceUpdates } from '../types'

type SortOrder = 'DESC' | 'ASC'
type RangeMode = 'rolling' | 'fixed'

const DAY_MS = 86_400_000
const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const rows = ref<ErrorLogItem[]>([])
const applications = ref<ApplicationOption[]>([])
const applicationNamespace = ref(String(route.query.applicationNamespace || route.query.service || ''))
const sourceId = ref<number | undefined>(route.query.sourceId ? Number(route.query.sourceId) : undefined)
const instances = computed(() => applications.value.find(item => item.applicationNamespace === applicationNamespace.value)?.instances || [])
const category = ref(String(route.query.category || ''))
const keyword = ref(String(route.query.keyword || ''))
const sort = ref<SortOrder>(route.query.sort === 'ASC' ? 'ASC' : 'DESC')
const rangeMode = ref<RangeMode>(route.query.mode === 'fixed' ? 'fixed' : 'rolling')
const validDate = (value: unknown, fallback: Date) => {
  const parsed = value ? new Date(String(value)) : fallback
  return Number.isNaN(parsed.getTime()) ? fallback : parsed
}
const initialTo = validDate(route.query.to, new Date())
const initialFrom = validDate(route.query.from, new Date(initialTo.getTime() - DAY_MS))
const fixedRange = ref<[Date, Date]>([initialFrom, initialTo])
const page = ref(Math.max(1, Number(route.query.page) || 1))
const pageSize = 50
const total = ref(0)
const snapshotId = ref(0)
const pendingCount = ref(0)
const paused = ref(false)
const hidden = ref(document.hidden)
const loading = ref(false)
const checking = ref(false)
const error = ref('')
const refreshError = ref('')
const surface = ref<HTMLElement>()
let timer: number | undefined

const autoRefreshActive = computed(() => !paused.value && !hidden.value)
const activeRange = () => rangeMode.value === 'rolling'
  ? [new Date(Date.now() - DAY_MS), new Date()] as [Date, Date]
  : fixedRange.value
const requestParams = () => {
  const [from, to] = activeRange()
  return {
    applicationNamespace: applicationNamespace.value || undefined,
    sourceId: sourceId.value,
    category: category.value || undefined,
    keyword: keyword.value.trim() || undefined,
    sort: sort.value,
    from: from.toISOString(),
    to: to.toISOString(),
  }
}

function queryState() {
  const query: Record<string, string> = {}
  if (applicationNamespace.value) query.applicationNamespace = applicationNamespace.value
  if (sourceId.value) query.sourceId = String(sourceId.value)
  if (category.value) query.category = category.value
  if (keyword.value.trim()) query.keyword = keyword.value.trim()
  if (sort.value !== 'DESC') query.sort = sort.value
  if (rangeMode.value === 'fixed') {
    query.mode = 'fixed'
    query.from = fixedRange.value[0].toISOString()
    query.to = fixedRange.value[1].toISOString()
  }
  if (page.value > 1) query.page = String(page.value)
  return query
}

async function syncUrl() {
  await router.replace({ path: '/errors/logs', query: queryState(), state: history.state })
}

async function load(resetPage = false) {
  if (resetPage) page.value = 1
  await syncUrl()
  if (!applicationNamespace.value) {
    rows.value = []
    total.value = 0
    snapshotId.value = 0
    pendingCount.value = 0
    return
  }
  loading.value = true
  error.value = ''
  try {
    const { data } = await api.get<ErrorOccurrencePage>('/errors/occurrences', {
      params: { ...requestParams(), page: page.value, pageSize },
    })
    rows.value = data.items
    total.value = data.total
    snapshotId.value = data.snapshotId
    pendingCount.value = 0
    refreshError.value = ''
    await nextTick()
    const restore = Number(history.state?.streamScrollTop || 0)
    const wrap = surface.value?.querySelector<HTMLElement>('.el-scrollbar__wrap')
    if (wrap && restore) wrap.scrollTop = restore
  } catch (reason) {
    error.value = errorMessage(reason)
  } finally {
    loading.value = false
  }
}

async function checkUpdates() {
  if (!applicationNamespace.value || !autoRefreshActive.value || checking.value || loading.value) return
  checking.value = true
  try {
    const { data } = await api.get<ErrorOccurrenceUpdates>('/errors/occurrences/updates', {
      params: { ...requestParams(), afterId: snapshotId.value },
    })
    refreshError.value = ''
    snapshotId.value = Math.max(snapshotId.value, data.latestId)
    if (!data.count) return
    const wrap = surface.value?.querySelector<HTMLElement>('.el-scrollbar__wrap')
    const atTop = !wrap || wrap.scrollTop <= 4
    if (sort.value === 'DESC' && page.value === 1 && atTop) await load()
    else pendingCount.value += data.count
  } catch (reason) {
    refreshError.value = errorMessage(reason)
  } finally {
    checking.value = false
  }
}

async function loadPending() {
  page.value = 1
  sort.value = 'DESC'
  await load()
  surface.value?.querySelector<HTMLElement>('.el-scrollbar__wrap')?.scrollTo({ top: 0 })
}

async function loadApplications() {
  try {
    const data = (await api.get<ApplicationOption[]>('/applications/options')).data
    applications.value = Array.isArray(data) ? data.filter(item => item && Array.isArray(item.instances)) : []
    if (applicationNamespace.value && !applications.value.some(item => item.applicationNamespace === applicationNamespace.value)) {
      applicationNamespace.value = ''
      sourceId.value = undefined
      page.value = 1
      return
    }
    if (sourceId.value && !instances.value.some(item => item.sourceId === sourceId.value)) sourceId.value = undefined
  } catch {}
}

async function refresh() {
  await loadApplications()
  await load()
}

async function togglePaused() {
  paused.value = !paused.value
  if (!paused.value) await checkUpdates()
}

async function open(row: ErrorLogItem) {
  const wrap = surface.value?.querySelector<HTMLElement>('.el-scrollbar__wrap')
  await router.replace({ path: '/errors/logs', query: queryState(), state: { ...history.state, streamScrollTop: wrap?.scrollTop || 0 } })
  await router.push({ path: `/errors/logs/${row.id}`, query: queryState(), state: { fromErrorStream: true } })
}

function visibilityChanged() {
  hidden.value = document.hidden
  if (!hidden.value && !paused.value) checkUpdates()
}

async function initialize() {
  await loadApplications()
  await load()
  timer = window.setInterval(checkUpdates, 10_000)
  document.addEventListener('visibilitychange', visibilityChanged)
}

onMounted(initialize)
onUnmounted(() => {
  clearInterval(timer)
  document.removeEventListener('visibilitychange', visibilityChanged)
})
const fmt = (value: string) => formatDateTime(value)
</script>

<template>
  <div class="page error-stream-page">
    <PageHeader :title="t('errors.streamTitle')" :subtitle="t('errors.streamSubtitle')" :loading="loading" @refresh="load()">
      <template #actions>
        <div class="stream-header-actions">
          <span class="auto-refresh-state" :class="{ paused: !autoRefreshActive }"><span class="status-dot"></span>{{ autoRefreshActive ? t('errors.autoRefreshActive') : t('errors.autoRefreshPaused') }}</span>
          <button class="icon-button" type="button" :title="paused?t('errors.resumeRefresh'):t('errors.pauseRefresh')" @click="togglePaused"><Play v-if="paused" :size="15"/><Pause v-else :size="15"/></button>
          <button class="icon-button" type="button" :title="t('common.refresh')" :disabled="loading" @click="refresh"><RefreshCw :size="15" :class="{spinning:loading}"/></button>
        </div>
      </template>
    </PageHeader>

    <section class="stream-filters">
      <el-select v-model="applicationNamespace" filterable :placeholder="t('errors.selectNamespace')" @change="sourceId=undefined;load(true)"><el-option v-for="item in applications" :key="item.applicationNamespace" :label="item.applicationNamespace" :value="item.applicationNamespace"/></el-select>
      <el-select v-model="sourceId" clearable filterable :disabled="!applicationNamespace" :placeholder="t('filter.allInstances')" @change="load(true)">
        <el-option v-for="item in instances" :key="item.sourceId" :label="item.label" :value="item.sourceId"><el-tooltip :content="item.label" placement="right"><span class="instance-option">{{ item.label }}</span></el-tooltip></el-option>
      </el-select>
      <el-segmented v-model="rangeMode" :options="[{label:t('errors.rolling24h'),value:'rolling'},{label:t('errors.customRange'),value:'fixed'}]" @change="load(true)"/>
      <el-date-picker v-if="rangeMode==='fixed'" v-model="fixedRange" type="datetimerange" :start-placeholder="t('filter.start')" :end-placeholder="t('filter.end')" @change="load(true)"/>
      <el-select v-model="category" :placeholder="t('common.all')" @change="load(true)"><el-option :label="t('common.all')" value=""/><el-option :label="t('errors.system')" value="SYSTEM"/><el-option :label="t('errors.business')" value="BUSINESS"/></el-select>
      <el-input v-model="keyword" clearable :placeholder="t('errors.search')" @keyup.enter="load(true)"><template #prefix><Search :size="14"/></template></el-input>
      <button class="secondary-button stream-sort" type="button" @click="sort=sort==='DESC'?'ASC':'DESC';load(true)"><ArrowDownUp :size="14"/>{{ sort==='DESC'?t('errors.newestFirst'):t('errors.oldestFirst') }}</button>
    </section>

    <div v-if="error" class="notice error">{{ error }}</div>
    <div v-else-if="refreshError" class="notice warning stream-refresh-error">{{ t('errors.refreshFailed') }} · {{ refreshError }}</div>
    <button v-if="pendingCount" class="new-errors-banner" type="button" @click="loadPending">{{ t('errors.newItems',{count:pendingCount}) }} · {{ t('errors.loadNewItems') }}</button>

    <EmptyState v-if="!applicationNamespace" :title="t('errors.selectNamespace')" :text="t('errors.selectNamespaceHint')"/>
    <section v-else ref="surface" class="data-surface stream-table" v-loading="loading">
      <div class="stream-count">{{ t('errors.streamCount',{count:total}) }}</div>
      <el-table :data="rows" height="calc(100vh - 310px)" @row-click="open">
        <el-table-column :label="t('errors.lastSeen')" width="178"><template #default="scope"><code>{{ fmt(scope.row.occurredAt) }}</code></template></el-table-column>
        <el-table-column :label="t('errors.type')" width="84"><template #default="scope"><span class="category-tag" :class="scope.row.category.toLowerCase()">{{ scope.row.category==='SYSTEM'?t('errors.system'):t('errors.business') }}</span></template></el-table-column>
        <el-table-column :label="t('errors.summary')" min-width="360"><template #default="scope"><div class="error-cell"><strong>{{ scope.row.summary }}</strong><span>{{ scope.row.exceptionClass||'LOG_ERROR' }}</span></div></template></el-table-column>
        <el-table-column :label="t('errors.serverInstance')" width="200"><template #default="scope"><span>{{ scope.row.displayAddress||scope.row.agentName||t('errors.localNode') }}</span><code class="table-subline">{{ scope.row.sourceName }}</code></template></el-table-column>
        <el-table-column prop="threadName" :label="t('errors.thread')" width="170" show-overflow-tooltip/>
        <el-table-column :label="t('errors.endpoint')" min-width="220"><template #default="scope"><span v-if="scope.row.inferredUri" class="inferred">{{ t('errors.inferred') }} · {{ scope.row.inferredUri }}</span><span v-else class="muted">{{ t('errors.unlinked') }}</span></template></el-table-column>
        <template #empty><EmptyState/></template>
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="pageSize" :total="total" layout="total, prev, pager, next" @current-change="load()"/>
    </section>
  </div>
</template>
