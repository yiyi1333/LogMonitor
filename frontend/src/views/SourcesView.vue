<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { CheckCircle2, CircleAlert, Clock3, FolderOpen, FolderPlus, Pencil, Plus, RefreshCw, Trash2, X } from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import PageHeader from '../components/PageHeader.vue'
import EmptyState from '../components/EmptyState.vue'
import { api, errorMessage } from '../api'
import type { AgentSummary, SourceOptions, SourceStatus } from '../types'
import { formatDateTime } from '../format'

const rows = ref<SourceStatus[]>([])
const agents = ref<AgentSummary[]>([])
const options = ref<SourceOptions>({ allowedRoots: [], defaultInclude: '*.log', defaultExclude: '*.error_*.log' })
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const namespaceDialog = ref(false)
const actionId = ref<number | null>(null)
const error = ref('')
const formError = ref('')
interface SourceDraft { name: string; applicationNamespace: string; path: string; include: string; exclude: string; startMode: 'NOW'|'HISTORY_180D'; error?: string }
const form = reactive<{ collectorType: 'LOCAL'|'AGENT'; agentId?: number; sources: SourceDraft[] }>({ collectorType: 'LOCAL', sources: [] })
const namespaceForm = reactive<{ id?: number; name: string; applicationNamespace: string }>({ name: '', applicationNamespace: '' })
const refreshTimers: number[] = []
const { t } = useI18n()

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = (await api.get<SourceStatus[]>('/sources/status')).data.map(item => ({
      ...item,
      applicationNamespace: item.applicationNamespace || item.sourceName,
      displayAddress: item.displayAddress || item.agentName || 'local',
      namespaceMigrationStatus: item.namespaceMigrationStatus || 'IDLE',
    }))
  } catch (reason) {
    error.value = errorMessage(reason)
  } finally {
    loading.value = false
  }
}

async function loadOptions() {
  if (form.collectorType === 'AGENT' && !form.agentId) {
    options.value = { ...options.value, allowedRoots: [] }
    return
  }
  try {
    options.value = (await api.get<SourceOptions>('/sources/options', { params: { agentId: form.collectorType === 'AGENT' ? form.agentId : undefined } })).data
  } catch (reason) {
    error.value = errorMessage(reason)
  }
}

function openCreate() {
  Object.assign(form, {
    collectorType: 'LOCAL',
    agentId: undefined,
    sources: [newDraft('HISTORY_180D')],
  })
  formError.value = ''
  dialog.value = true
  void loadOptions()
}

async function collectorChanged() {
  form.sources = [newDraft(form.collectorType === 'AGENT' ? 'NOW' : 'HISTORY_180D')]
  await loadOptions()
}

function newDraft(startMode: 'NOW'|'HISTORY_180D'): SourceDraft {
  return { name: '', applicationNamespace: '', path: '', include: options.value.defaultInclude, exclude: options.value.defaultExclude, startMode }
}

function addDraft() {
  if (form.sources.length < 50) form.sources.push(newDraft(form.collectorType === 'AGENT' ? 'NOW' : 'HISTORY_180D'))
}

function removeDraft(index: number) {
  if (form.sources.length > 1) form.sources.splice(index, 1)
}

async function createSource() {
  formError.value = ''
  if (form.collectorType === 'AGENT' && !form.agentId) {
    formError.value = t('sources.agentRequired')
    return
  }
  const pathSet = new Set<string>()
  for (const [index, row] of form.sources.entries()) {
    row.error = undefined
    const validName = /^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/
    if (!validName.test(row.name.trim()) || !validName.test(row.applicationNamespace.trim())) row.error = t('sources.nameRule')
    else if (!row.path.trim().startsWith('/')) row.error = t('sources.pathRule')
    else if (!row.include.trim()) row.error = t('sources.includeRequired')
    else if (pathSet.has(row.path.trim())) row.error = t('sources.pathDuplicate')
    pathSet.add(row.path.trim())
    if (row.error && !formError.value) formError.value = t('sources.rowError', { row: index + 1, error: row.error })
  }
  if (formError.value) return

  saving.value = true
  try {
    await api.post('/sources/batch', {
      collectorType: form.collectorType,
      agentId: form.collectorType === 'AGENT' ? form.agentId : undefined,
      sources: form.sources.map(row => ({
        name: row.name.trim(), applicationNamespace: row.applicationNamespace.trim(), path: row.path.trim(),
        include: row.include.trim(), exclude: row.exclude.trim(), startMode: row.startMode,
      })),
    })
    dialog.value = false
    ElMessage.success(t('sources.batchAdded',{count:form.sources.length}))
    await load()
    scheduleStatusRefresh()
  } catch (reason) {
    formError.value = errorMessage(reason)
  } finally {
    saving.value = false
  }
}

function openNamespace(row: SourceStatus) {
  Object.assign(namespaceForm, { id: row.id, name: row.sourceName, applicationNamespace: row.applicationNamespace })
  formError.value = ''
  namespaceDialog.value = true
}

async function updateNamespace() {
  const value = namespaceForm.applicationNamespace.trim()
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(value)) { formError.value = t('sources.nameRule'); return }
  saving.value = true
  try {
    await api.patch(`/sources/${namespaceForm.id}/namespace`, { applicationNamespace: value })
    namespaceDialog.value = false
    ElMessage.success(t('sources.migrationQueued'))
    await load()
    scheduleStatusRefresh()
  } catch (reason) { formError.value = errorMessage(reason) }
  finally { saving.value = false }
}

async function deleteSource(row: SourceStatus) {
  try {
    await ElMessageBox.confirm(
      t('sources.deleteConfirm',{name:row.sourceName,path:row.path}), t('sources.deleteTitle',{name:row.sourceName}),
      { confirmButtonText: t('sources.deleteDirectory'), cancelButtonText: t('common.cancel'), type: 'warning' },
    )
  } catch (reason) {
    if (reason === 'cancel' || reason === 'close') return
    error.value = errorMessage(reason)
    return
  }

  actionId.value = row.id
  error.value = ''
  try {
    await api.delete(`/sources/${row.id}`)
    ElMessage.success(t('sources.deleted',{name:row.sourceName}))
    await load()
  } catch (reason) {
    error.value = errorMessage(reason)
  } finally {
    actionId.value = null
  }
}

function scheduleStatusRefresh() {
  clearRefreshTimers()
  for (const delay of [1500, 3500, 7000]) {
    refreshTimers.push(window.setTimeout(load, delay))
  }
}

function clearRefreshTimers() {
  refreshTimers.splice(0).forEach(timer => window.clearTimeout(timer))
}

const active = computed(() => rows.value.filter(item => item.status === 'ACTIVE').length)
const issues = computed(() => rows.value.filter(item => ['ERROR','OFFLINE','BLOCKED'].includes(item.status)).length)
const fmtBytes = (value: number) => {
  if (!value) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), 4)
  return `${(value / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`
}
const fmtTime = (value?: string) => value ? formatDateTime(value) : t('sources.neverCollected')
const sourceState = (status: string) => ({ ACTIVE:t('sources.active'), ERROR:t('sources.error'), OFFLINE:t('sources.offline'), BLOCKED:t('sources.blocked'), MIGRATING:t('sources.migrating'), FAILED:t('sources.migrationFailed') }[status] || t('sources.validating'))

onMounted(async () => {
  const agentRequest = api.get<AgentSummary[]>('/agents').then(response => { agents.value = response.data }).catch(reason => { error.value = errorMessage(reason) })
  await Promise.all([load(), loadOptions(), agentRequest])
})
onBeforeUnmount(clearRefreshTimers)
</script>

<template>
  <div class="page">
    <PageHeader :title="t('sources.title')" :subtitle="t('sources.subtitle')" :loading="loading" @refresh="load">
      <template #actions>
        <div class="global-filters">
          <button class="icon-button refresh" type="button" :title="t('sources.refresh')" :disabled="loading" @click="load">
            <RefreshCw :size="16" :class="{ spinning: loading }"/>
          </button>
          <button class="primary-button compact" type="button" @click="openCreate">
            <FolderPlus :size="16"/><span>{{ t('sources.addDirectory') }}</span>
          </button>
        </div>
      </template>
    </PageHeader>
    <div v-if="error" class="notice error">{{ error }}</div>
    <section class="source-summary">
      <span><CheckCircle2 :size="16"/>{{ t('sources.activeCount',{count:active}) }}</span>
      <span :class="{ 'text-danger': issues }"><CircleAlert :size="16"/>{{ t('sources.issueCount',{count:issues}) }}</span>
      <span><Clock3 :size="16"/>{{ t('sources.scanInterval') }}</span>
    </section>
    <section class="source-grid" v-loading="loading">
      <article v-for="item in rows" :key="item.id" class="source-row">
        <div class="source-title">
          <span class="source-icon"><FolderOpen :size="18"/></span>
          <div><h2>{{ item.sourceName }} <span class="namespace-label">{{ item.applicationNamespace }}</span></h2><code>{{ item.displayAddress || item.agentName || t('sources.localNode') }} · {{ item.path }}</code></div>
          <span class="source-state" :class="(item.namespaceMigrationStatus === 'IDLE' ? item.status : item.namespaceMigrationStatus).toLowerCase()">
            <span class="status-dot"></span>{{ sourceState(item.namespaceMigrationStatus === 'IDLE' ? item.status : item.namespaceMigrationStatus) }}
          </span>
          <el-tooltip :content="t('sources.changeNamespace')" placement="top">
            <button class="row-action" type="button" :aria-label="t('sources.changeNamespace')" :disabled="actionId === item.id || item.namespaceMigrationStatus === 'MIGRATING'" @click="openNamespace(item)"><Pencil :size="15"/></button>
          </el-tooltip>
          <el-tooltip :content="t('sources.deleteDirectory')" placement="top">
            <button class="row-action danger source-delete" type="button" :aria-label="t('sources.deleteTitle',{name:item.sourceName})"
              :disabled="actionId === item.id" @click="deleteSource(item)"><Trash2 :size="15"/></button>
          </el-tooltip>
        </div>
        <div class="source-progress">
          <div><span>{{ t('sources.readProgress') }}</span><b>{{ fmtBytes(item.bytesRead) }} / {{ fmtBytes(item.totalBytes) }}</b></div>
          <el-progress :percentage="item.totalBytes ? Math.min(100, Math.round(item.bytesRead / item.totalBytes * 100)) : 0" :show-text="false"/>
        </div>
        <dl>
          <div><dt>{{ t('sources.files') }}</dt><dd>{{ item.files }}</dd></div>
          <div><dt>{{ t('sources.include') }}</dt><dd><code>{{ item.include }}</code></dd></div>
          <div><dt>{{ t('sources.exclude') }}</dt><dd><code>{{ item.exclude || t('sources.none') }}</code></dd></div>
          <div><dt>{{ t('sources.lastCollected') }}</dt><dd>{{ fmtTime(item.lastCollectedAt) }}</dd></div>
          <div><dt>{{ t('sources.parseErrors') }}</dt><dd :class="{ 'text-danger': item.parseErrors }">{{ item.parseErrors }}</dd></div>
        </dl>
        <p v-if="item.lastError" class="notice error">{{ item.lastError }}</p>
      </article>
      <EmptyState v-if="!rows.length" :title="t('sources.emptyTitle')" :text="t('sources.emptyText')"/>
    </section>

    <el-dialog v-model="dialog" :title="t('sources.batchDialogTitle')" width="min(1120px, calc(100vw - 28px))" :close-on-click-modal="false">
      <form class="source-form" @submit.prevent="createSource">
        <label>{{ t('sources.collectorNode') }}
          <el-select v-model="form.collectorType" @change="collectorChanged">
            <el-option :label="t('sources.localNode')" value="LOCAL"/>
            <el-option :label="t('sources.remoteAgent')" value="AGENT"/>
          </el-select>
        </label>
        <label v-if="form.collectorType==='AGENT'">{{ t('sources.remoteServer') }}
          <el-select v-model="form.agentId" :placeholder="t('sources.selectAgent')" @change="collectorChanged">
            <el-option v-for="agent in agents" :key="agent.id" :label="`${agent.displayAddress || agent.hostName} · ${agent.name}`" :value="agent.id"/>
          </el-select>
        </label>
        <div v-if="options.allowedRoots.length" class="allowed-roots">
          <span>{{ t('sources.allowedRoots') }}</span><code v-for="root in options.allowedRoots" :key="root">{{ root }}</code>
        </div>
        <div class="batch-source-heading">
          <strong>{{ t('sources.directoryRows',{count:form.sources.length}) }}</strong>
          <button class="secondary-button compact" type="button" :disabled="form.sources.length>=50" @click="addDraft"><Plus :size="14"/>{{ t('sources.addRow') }}</button>
        </div>
        <div class="batch-source-list">
          <section v-for="(row,index) in form.sources" :key="index" class="batch-source-row" :class="{invalid:row.error}">
            <header><b>{{ t('sources.directoryRow',{row:index+1}) }}</b><button class="row-action" type="button" :disabled="form.sources.length===1" :title="t('sources.removeRow')" @click="removeDraft(index)"><X :size="14"/></button></header>
            <div class="batch-source-main">
              <label>{{ t('sources.serviceName') }}<el-input v-model="row.name" maxlength="80" autocomplete="off" :placeholder="t('sources.serviceExample')"/></label>
              <label>{{ t('sources.applicationNamespace') }}<el-input v-model="row.applicationNamespace" maxlength="80" autocomplete="off" :placeholder="t('sources.namespaceExample')"/></label>
              <label class="batch-path">{{ t('sources.absolutePath') }}<el-input v-model="row.path" maxlength="1500" autocomplete="off" placeholder="/data/logs/account-service"/></label>
            </div>
            <div class="batch-source-rules">
              <label>{{ t('sources.include') }}<el-input v-model="row.include" maxlength="255" autocomplete="off"/></label>
              <label>{{ t('sources.exclude') }}<el-input v-model="row.exclude" maxlength="255" autocomplete="off"/></label>
              <label>{{ t('sources.startMode') }}<el-select v-model="row.startMode"><el-option :label="t('sources.startNow')" value="NOW"/><el-option :label="t('sources.history')" value="HISTORY_180D"/></el-select></label>
            </div>
            <p v-if="row.error" class="form-error row-error">{{ row.error }}</p>
          </section>
        </div>
        <p v-if="formError" class="form-error">{{ formError }}</p>
      </form>
      <template #footer>
        <button class="secondary-button" type="button" @click="dialog = false">{{ t('common.cancel') }}</button>
        <button class="primary-button compact" type="button" :disabled="saving" @click="createSource">{{ saving ? t('sources.adding') : t('sources.addDirectory') }}</button>
      </template>
    </el-dialog>

    <el-dialog v-model="namespaceDialog" :title="t('sources.changeNamespace')" width="min(440px, calc(100vw - 28px))" :close-on-click-modal="false">
      <form class="source-form" @submit.prevent="updateNamespace">
        <div class="notice warning">{{ t('sources.migrationHint',{name:namespaceForm.name}) }}</div>
        <label>{{ t('sources.applicationNamespace') }}<el-input v-model="namespaceForm.applicationNamespace" maxlength="80" autocomplete="off"/></label>
        <p v-if="formError" class="form-error">{{ formError }}</p>
      </form>
      <template #footer><button class="secondary-button" type="button" @click="namespaceDialog=false">{{ t('common.cancel') }}</button><button class="primary-button compact" type="button" :disabled="saving" @click="updateNamespace">{{ t('common.save') }}</button></template>
    </el-dialog>
  </div>
</template>
