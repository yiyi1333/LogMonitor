<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowLeft, Link2 } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import AiAnalysisPanel from '../components/AiAnalysisPanel.vue'
import EmptyState from '../components/EmptyState.vue'
import { api, errorMessage } from '../api'
import { formatDateTime } from '../format'
import type { ErrorOccurrenceDetail } from '../types'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const id = Number(route.params.occurrenceId)
const detail = ref<ErrorOccurrenceDetail>()
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try { detail.value = (await api.get<ErrorOccurrenceDetail>(`/errors/occurrences/${id}`)).data }
  catch (reason) { error.value = errorMessage(reason) }
  finally { loading.value = false }
}

function back() {
  if (history.state?.fromErrorStream) router.back()
  else router.push({ path: '/errors/logs', query: route.query })
}

onMounted(load)
const fmt = (value: string) => formatDateTime(value)
</script>

<template>
  <div class="page occurrence-detail-page" v-loading="loading">
    <header class="occurrence-detail-header">
      <button class="icon-button" type="button" :title="t('errors.backToStream')" @click="back"><ArrowLeft :size="17"/></button>
      <div><span>{{ t('errors.detailTitle') }}</span><h1>{{ detail?.summary || t('common.loading') }}</h1></div>
    </header>
    <div v-if="error" class="notice error">{{ error }}</div>
    <template v-if="detail">
      <section class="occurrence-overview">
        <div class="occurrence-primary"><span class="category-tag" :class="detail.category.toLowerCase()">{{ detail.category==='SYSTEM'?t('errors.systemException'):t('errors.businessException') }}</span><code>{{ detail.exceptionClass||'LOG_ERROR' }}</code><time>{{ fmt(detail.occurredAt) }}</time></div>
        <dl class="occurrence-meta">
          <div><dt>{{ t('errors.service') }}</dt><dd>{{ detail.service }}</dd></div>
          <div><dt>{{ t('errors.serverInstance') }}</dt><dd>{{ detail.agentName||t('errors.localNode') }}<code>{{ detail.instanceKey }}</code></dd></div>
          <div><dt>{{ t('errors.thread') }}</dt><dd><code>{{ detail.threadName||t('common.unknown') }}</code></dd></div>
          <div><dt>{{ t('errors.source') }}</dt><dd>{{ detail.sourceName||t('common.unknown') }}<code>{{ detail.sourcePath||t('errors.unknownFile') }}</code></dd></div>
          <div><dt>{{ t('errors.fileOffset') }}</dt><dd><code>{{ detail.sourceOffset??t('common.unknown') }}</code></dd></div>
          <div><dt>{{ t('errors.fingerprint') }}</dt><dd><code>{{ detail.fingerprint }}</code></dd></div>
        </dl>
        <div class="occurrence-link"><Link2 :size="14"/><span v-if="detail.inferredUri">{{ t('errors.inferred') }} · {{ detail.inferredUri }}</span><span v-else>{{ t('errors.unlinked') }}</span></div>
      </section>
      <section class="detail-section occurrence-log-section"><div class="section-heading"><div><h2>{{ t('errors.redactedMessage') }}</h2></div></div><pre class="stack-trace">{{ detail.messageText }}</pre></section>
      <section class="detail-section occurrence-log-section"><div class="section-heading"><div><h2>{{ t('errors.redactedStack') }}</h2></div></div><pre v-if="detail.stackTrace" class="stack-trace">{{ detail.stackTrace }}</pre><EmptyState v-else :title="t('errors.noStack')"/></section>
      <AiAnalysisPanel target-type="occurrences" :target-id="detail.id"/>
    </template>
  </div>
</template>
