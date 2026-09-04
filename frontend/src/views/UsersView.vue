<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { CirclePause, CirclePlay, Trash2, UserPlus } from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { api, errorMessage } from '../api'
import EmptyState from '../components/EmptyState.vue'
import type { PageResult, UserSummary } from '../types'
import { formatDateTime } from '../format'

const rows = ref<UserSummary[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const actionId = ref<number | null>(null)
const error = ref('')
const formError = ref('')
const form = reactive({ username: '', initialPassword: '', confirmPassword: '' })
const { t } = useI18n()

async function load() {
  loading.value = true
  error.value = ''
  try {
    const { data } = await api.get<PageResult<UserSummary>>('/users', { params: { page: page.value, pageSize: pageSize.value } })
    rows.value = data.items
    total.value = data.total
  } catch (reason) {
    error.value = errorMessage(reason)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, { username: '', initialPassword: '', confirmPassword: '' })
  formError.value = ''
  dialog.value = true
}

async function createUser() {
  formError.value = ''
  if (!/^[A-Za-z0-9._-]{3,32}$/.test(form.username.trim())) {
    formError.value = t('users.usernameRule')
    return
  }
  if (form.initialPassword.length < 8 || form.initialPassword.length > 64) {
    formError.value = t('users.passwordRule')
    return
  }
  if (form.initialPassword !== form.confirmPassword) {
    formError.value = t('users.passwordMismatch')
    return
  }
  saving.value = true
  try {
    await api.post('/users', { username: form.username.trim(), initialPassword: form.initialPassword })
    dialog.value = false
    page.value = 1
    await load()
  } catch (reason) {
    formError.value = errorMessage(reason)
  } finally {
    saving.value = false
  }
}

function accountState(row: UserSummary) {
  if (!row.enabled) return { label: t('users.disabled'), className: 'disabled' }
  if (row.mustChangePassword) return { label: t('users.pending'), className: 'pending' }
  return { label: t('users.normal'), className: '' }
}

function cancelled(reason: unknown) {
  return reason === 'cancel' || reason === 'close'
}

async function changeStatus(row: UserSummary) {
  const nextEnabled = !row.enabled
  const action = nextEnabled ? t('common.enable') : t('common.disable')
  try {
    await ElMessageBox.confirm(
      t('users.statusConfirm',{action}), t('users.actionTitle',{action,username:row.username}),
      { confirmButtonText: action, cancelButtonText: t('common.cancel'), type: 'warning' },
    )
  } catch (reason) {
    if (cancelled(reason)) return
    error.value = errorMessage(reason)
    return
  }

  actionId.value = row.id
  error.value = ''
  try {
    await api.patch(`/users/${row.id}/status`, { enabled: nextEnabled })
    ElMessage.success(t('users.actionDone',{action,username:row.username}))
    await load()
  } catch (reason) {
    error.value = errorMessage(reason)
  } finally {
    actionId.value = null
  }
}

async function deleteUser(row: UserSummary) {
  try {
    await ElMessageBox.confirm(
      t('users.deleteConfirm',{username:row.username}), t('users.actionTitle',{action:t('common.delete'),username:row.username}),
      { confirmButtonText: t('users.permanentDelete'), cancelButtonText: t('common.cancel'), type: 'error' },
    )
  } catch (reason) {
    if (cancelled(reason)) return
    error.value = errorMessage(reason)
    return
  }

  actionId.value = row.id
  error.value = ''
  try {
    await api.delete(`/users/${row.id}`)
    if (rows.value.length === 1 && page.value > 1) page.value -= 1
    ElMessage.success(t('users.deleted',{username:row.username}))
    await load()
  } catch (reason) {
    error.value = errorMessage(reason)
  } finally {
    actionId.value = null
  }
}

const formatTime = (value: string) => formatDateTime(value)
onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-header users-header">
      <div><h1>{{ t('users.title') }}</h1><p>{{ t('users.subtitle') }}</p></div>
      <button class="primary-button compact" type="button" @click="openCreate"><UserPlus :size="16"/><span>{{ t('users.create') }}</span></button>
    </header>
    <div v-if="error" class="notice error">{{ error }}</div>
    <section class="data-surface" v-loading="loading">
      <el-table :data="rows" height="calc(100vh - 190px)">
        <el-table-column prop="username" :label="t('users.username')" min-width="220"><template #default="scope"><code>{{ scope.row.username }}</code></template></el-table-column>
        <el-table-column :label="t('users.role')" width="150"><template #default>{{ t('users.regular') }}</template></el-table-column>
        <el-table-column :label="t('users.status')" width="180"><template #default="scope"><span class="user-state" :class="accountState(scope.row).className">{{ accountState(scope.row).label }}</span></template></el-table-column>
        <el-table-column :label="t('users.createdAt')" width="220"><template #default="scope">{{ formatTime(scope.row.createdAt) }}</template></el-table-column>
        <el-table-column :label="t('users.actions')" width="130"><template #default="scope"><div class="user-actions">
          <el-tooltip :content="scope.row.enabled ? t('users.disableAccount') : t('users.enableAccount')" placement="top">
            <button class="row-action" type="button" :aria-label="`${scope.row.enabled ? t('common.disable') : t('common.enable')} ${scope.row.username}`" :disabled="actionId === scope.row.id" @click="changeStatus(scope.row)"><CirclePause v-if="scope.row.enabled" :size="15"/><CirclePlay v-else :size="15"/></button>
          </el-tooltip>
          <el-tooltip :content="t('users.permanentDelete')" placement="top">
            <button class="row-action danger" type="button" :aria-label="`${t('common.delete')} ${scope.row.username}`" :disabled="actionId === scope.row.id" @click="deleteUser(scope.row)"><Trash2 :size="15"/></button>
          </el-tooltip>
        </div></template></el-table-column>
        <template #empty><EmptyState :title="t('users.emptyTitle')" :text="t('users.emptyText')"/></template>
      </el-table>
      <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="total" layout="total, prev, pager, next" @current-change="load"/>
    </section>

    <el-dialog v-model="dialog" :title="t('users.dialogTitle')" width="min(460px, calc(100vw - 28px))" :close-on-click-modal="false">
      <form class="user-form" @submit.prevent="createUser">
        <label>{{ t('users.username') }}<el-input v-model="form.username" maxlength="32" autocomplete="off" :placeholder="t('users.usernamePlaceholder')"/></label>
        <label>{{ t('users.temporaryPassword') }}<el-input v-model="form.initialPassword" type="password" show-password maxlength="64" autocomplete="new-password"/></label>
        <label>{{ t('users.confirmPassword') }}<el-input v-model="form.confirmPassword" type="password" show-password maxlength="64" autocomplete="new-password"/></label>
        <p v-if="formError" class="form-error">{{ formError }}</p>
      </form>
      <template #footer><button class="secondary-button" type="button" @click="dialog=false">{{ t('common.cancel') }}</button><button class="primary-button compact" type="button" :disabled="saving" @click="createUser">{{ saving ? t('users.creating') : t('users.create') }}</button></template>
    </el-dialog>
  </div>
</template>
