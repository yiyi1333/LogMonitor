<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Ban, ChevronDown, CircleAlert, FolderOpen, HardDrive, RefreshCw, Server } from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import PageHeader from '../components/PageHeader.vue'
import EmptyState from '../components/EmptyState.vue'
import { api, errorMessage } from '../api'
import type { AgentSummary, SourceStatus } from '../types'
import { formatDateTime } from '../format'

const rows = ref<AgentSummary[]>([])
const sources = ref<SourceStatus[]>([])
const loading = ref(false)
const actionId = ref<number>()
const error = ref('')
let inFlight: Promise<void> | undefined
let timer: number | undefined
let disposed = false
const { t } = useI18n()
const statusText = (status: string) => ({ ONLINE: t('agents.online'), OFFLINE: t('agents.offline'), BLOCKED: t('agents.blocked'), ERROR: t('agents.error'), WAITING: t('agents.waiting') }[status] || status)
const fmtBytes = (value: number) => {
  if (!value) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), 4)
  return `${(value / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`
}
const fmtTime = (value?: string) => value ? formatDateTime(value) : t('agents.neverConnected')
const online = computed(() => rows.value.filter(row => row.status === 'ONLINE').length)
const agentSources = (id: number): SourceStatus[] => sources.value.filter(item => item.collectorType === 'AGENT' && item.agentId === id)

function load(background = false): Promise<void> {
  if (disposed) return Promise.resolve()
  if (inFlight) return inFlight
  loading.value = !background
  inFlight = (async () => {
    try {
      const [agents, sourceStatuses] = await Promise.allSettled([api.get<AgentSummary[]>('/agents'), api.get<SourceStatus[]>('/sources/status')])
      if (disposed) return
      if (agents.status === 'rejected') throw agents.reason
      if (sourceStatuses.status === 'rejected') throw sourceStatuses.reason
      rows.value = agents.value.data
      sources.value = Array.isArray(sourceStatuses.value.data)
        ? sourceStatuses.value.data.filter(source => source && source.collectorType === 'AGENT')
        : []
      error.value = ''
    }
    catch (reason) { if (!disposed) error.value = errorMessage(reason) }
    finally { loading.value = false; inFlight = undefined }
  })()
  return inFlight
}

function refreshAutomatically() {
  if (!document.hidden && actionId.value === undefined) void load(true)
}

async function revoke(row: AgentSummary) {
  try {
    await ElMessageBox.confirm(
      t('agents.revokeConfirm',{name:row.name}), t('agents.revokeTitle',{name:row.name}),
      { confirmButtonText: t('agents.revoke'), cancelButtonText: t('common.cancel'), type: 'warning' },
    )
  } catch (reason) {
    if (reason === 'cancel' || reason === 'close') return
    error.value = errorMessage(reason)
    return
  }
  actionId.value = row.id
  try {
    await api.delete(`/agents/${row.id}`)
    ElMessage.success(t('agents.revoked',{name:row.name}))
    await inFlight
    await load()
  } catch (reason) { error.value = errorMessage(reason) }
  finally { actionId.value = undefined }
}

onMounted(() => {
  void load()
  timer = window.setInterval(refreshAutomatically, 10_000)
  document.addEventListener('visibilitychange', refreshAutomatically)
})
onUnmounted(() => {
  disposed = true
  window.clearInterval(timer)
  document.removeEventListener('visibilitychange', refreshAutomatically)
})
</script>

<template>
  <div class="page">
    <PageHeader :title="t('agents.title')" :subtitle="t('agents.subtitle')" :loading="loading" @refresh="load()">
      <template #actions>
        <button class="icon-button refresh" type="button" :title="t('agents.refresh')" :disabled="loading" @click="load()">
          <RefreshCw :size="16" :class="{ spinning: loading }"/>
        </button>
      </template>
    </PageHeader>
    <div v-if="error" class="notice error">{{ error }}</div>
    <section class="source-summary">
      <span><Server :size="16"/>{{ t('agents.registered',{count:rows.length}) }}</span>
      <span><span class="status-dot"></span>{{ t('agents.onlineCount',{count:online}) }}</span>
      <span v-if="rows.length-online" class="text-danger"><CircleAlert :size="16"/>{{ t('agents.attention',{count:rows.length-online}) }}</span>
    </section>
    <section class="agent-grid" v-loading="loading">
      <article v-for="item in rows" :key="item.id" class="agent-row">
        <div class="agent-title">
          <span class="source-icon"><Server :size="18"/></span>
          <div><h2>{{ item.name }}</h2><code>{{ item.displayAddress || item.hostName }} · {{ item.uuid }}</code></div>
          <span class="source-state" :class="item.status.toLowerCase()"><span class="status-dot"></span>{{ statusText(item.status) }}</span>
          <el-tooltip :content="t('agents.revoke')" placement="top"><button class="row-action danger" type="button" :aria-label="t('agents.revokeTitle',{name:item.name})" :disabled="actionId===item.id" @click="revoke(item)"><Ban :size="15"/></button></el-tooltip>
        </div>
        <div class="agent-queue"><div><HardDrive :size="14"/><span>{{ t('agents.diskQueue') }}</span><b>{{ fmtBytes(item.spoolBytes) }} / {{ fmtBytes(item.spoolLimitBytes) }}</b></div><el-progress :percentage="item.spoolLimitBytes ? Math.min(100, Math.round(item.spoolBytes/item.spoolLimitBytes*100)) : 0" :show-text="false"/></div>
        <dl>
          <div><dt>{{ t('agents.version') }}</dt><dd>{{ item.version }}</dd></div>
          <div><dt>{{ t('agents.lastHeartbeat') }}</dt><dd>{{ fmtTime(item.lastSeenAt) }}</dd></div>
          <div><dt>{{ t('agents.createdBy') }}</dt><dd>{{ item.createdBy }}</dd></div>
          <div><dt>{{ t('agents.allowedRoots') }}</dt><dd><code>{{ item.allowedRoots.join(' · ') }}</code></dd></div>
          <div><dt>{{ t('agents.mountedDirectories') }}</dt><dd>{{ agentSources(item.id).length }}</dd></div>
        </dl>
        <el-collapse v-if="agentSources(item.id).length" class="agent-sources">
          <el-collapse-item>
            <template #title><ChevronDown :size="14"/><span>{{ t('agents.viewDirectories',{count:agentSources(item.id).length}) }}</span></template>
            <div v-for="source in agentSources(item.id)" :key="source.id" class="agent-source-line">
              <FolderOpen :size="14"/><strong>{{ source.sourceName }}</strong><span>{{ source.applicationNamespace }}</span><code :title="source.path">{{ source.path }}</code>
            </div>
          </el-collapse-item>
        </el-collapse>
        <p v-if="item.lastError" class="notice error">{{ item.lastError }}</p>
      </article>
      <EmptyState v-if="!rows.length" :title="t('agents.emptyTitle')" :text="t('agents.emptyText')"/>
    </section>
  </div>
</template>
