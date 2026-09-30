<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ChevronDown, ChevronRight, ArrowLeft, RefreshCw } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import { api, errorMessage } from '../api'
import type { DirectoryListing } from '../types'

const props = defineProps<{ modelValue: string; agentId?: number; active: boolean; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const { t } = useI18n()
const visible = ref(false)
const loading = ref(false)
const error = ref('')
const search = ref('')
const listing = ref<DirectoryListing>({ path: null, parentPath: null, directories: [], truncated: false })
let generation = 0
let timer: ReturnType<typeof setTimeout> | undefined
let controller: AbortController | undefined

function cancel() {
  ++generation
  if (timer) clearTimeout(timer)
  controller?.abort()
  loading.value = false
}
function close() { visible.value = false; cancel() }
async function load(path: string | null, query = '') {
  cancel()
  const request = generation
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try {
    const response = await api.get<DirectoryListing>('/sources/directories', {
      params: { agentId: props.agentId, path: path || undefined, query: query || undefined },
      signal: controller.signal, timeout: 10000,
    })
    if (request === generation && visible.value) listing.value = response.data
  } catch (reason) {
    if (request === generation && visible.value) error.value = errorMessage(reason)
  } finally { if (request === generation) loading.value = false }
}
function popoverVisibility(value: boolean) { if (!value) close() }
function toggle() {
  if (props.disabled || !props.active) return
  if (visible.value) { close(); return }
  visible.value = true
  search.value = ''
  listing.value = { path: null, parentPath: null, directories: [], truncated: false }
  void load(null)
}
function enter(path: string | null) { search.value = ''; void load(path) }
function select() {
  if (listing.value.path) { emit('update:modelValue', listing.value.path); close() }
}
function searchChanged(value: string) {
  search.value = value
  cancel()
  const request = generation
  loading.value = true
  timer = setTimeout(() => { if (request === generation) void load(listing.value.path, value) }, 300)
}
watch(() => [props.agentId, props.active, props.disabled], close)
onBeforeUnmount(cancel)
</script>

<template>
  <el-popover :visible="visible" trigger="click" placement="bottom-start" :width="480" @hide="close" @update:visible="popoverVisibility" :popper-style="{ maxWidth: 'calc(100vw - 24px)' }">
    <template #reference>
      <el-input :model-value="modelValue" maxlength="1500" autocomplete="off" placeholder="/data/logs/account-service" @update:model-value="emit('update:modelValue', $event)">
        <template #suffix><button class="browse-toggle" type="button" :aria-label="t('sources.browse')" :aria-expanded="visible" :disabled="disabled" @click.stop="toggle"><ChevronDown :size="16" /></button></template>
      </el-input>
    </template>
    <section class="directory-browser" :aria-label="t('sources.browse')">
      <div class="directory-toolbar">
        <button type="button" :disabled="loading || !listing.path" @click="enter(listing.parentPath)"><ArrowLeft :size="14"/>{{ t('sources.directoryBack') }}</button>
        <button type="button" :disabled="loading" :title="t('common.refresh')" @click="load(listing.path, search)"><RefreshCw :size="14"/></button>
        <button type="button" @click="close">{{ t('common.close') }}</button>
      </div>
      <code class="directory-current">{{ listing.path || t('sources.allowedRoots') }}</code>
      <el-input :model-value="search" :disabled="!listing.path" :placeholder="t('sources.directorySearch')" @update:model-value="searchChanged"/>
      <p v-if="loading" role="status">{{ t('common.loading') }}</p>
      <p v-else-if="error" class="directory-error" role="alert">{{ error }}</p>
      <ul v-else class="directory-children">
        <li v-for="entry in listing.directories" :key="entry.path + entry.name"><button type="button" @click="enter(entry.path)"><span>{{ entry.name }}</span><ChevronRight :size="14"/></button></li>
        <li v-if="!listing.directories.length" class="directory-empty">{{ t('sources.directoryEmpty') }}</li>
      </ul>
      <p v-if="!loading && !error && listing.truncated">{{ t('sources.directoryTruncated') }}</p>
      <button class="directory-select" type="button" :disabled="loading || !!error || !listing.path" @click="select">{{ t('sources.directorySelect') }}</button>
      <p class="directory-hint">{{ t('sources.directoryManual') }}</p>
    </section>
  </el-popover>
</template>

<style scoped>
.browse-toggle { display:flex; border:0; background:transparent; cursor:pointer; color:inherit; padding:4px; }
.directory-toolbar { display:flex; align-items:center; gap:8px; margin-bottom:8px; }
.directory-toolbar button, .directory-children button { display:flex; align-items:center; gap:5px; border:0; background:transparent; color:inherit; cursor:pointer; padding:6px; }
.directory-toolbar button:disabled { cursor:default; opacity:.5; }
.directory-toolbar button:last-child { margin-left:auto; }
.directory-current { display:block; overflow-wrap:anywhere; margin-bottom:8px; }
.directory-children { list-style:none; padding:0; margin:8px 0; max-height:240px; overflow:auto; }
.directory-children button { width:100%; justify-content:space-between; text-align:left; }
.directory-children button:hover { background:var(--el-fill-color-light); }
.directory-children span { overflow-wrap:anywhere; }
.directory-select { width:100%; padding:8px; border:1px solid var(--el-border-color); background:var(--el-fill-color-light); color:inherit; cursor:pointer; }
.directory-select:disabled { cursor:default; opacity:.5; }
.directory-error { color:var(--el-color-danger); }
.directory-hint, .directory-empty { color:var(--el-text-color-secondary); font-size:12px; }
</style>
