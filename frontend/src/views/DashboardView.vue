<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowUpRight, CircleAlert, Gauge, Layers3, Radio } from 'lucide-vue-next'
import PageHeader from '../components/PageHeader.vue'
import TrendChart from '../components/TrendChart.vue'
import EmptyState from '../components/EmptyState.vue'
import { api, errorMessage } from '../api'
import { useFilterStore, type TimeRangePreset } from '../stores/filter'
import type { Dashboard } from '../types'
import { formatNumber } from '../format'
const filter=useFilterStore(), data=ref<Dashboard>(), loading=ref(false), error=ref('')
const { t } = useI18n()
const quickRanges = computed<{ label: string; value: TimeRangePreset }[]>(() => [
  { label: t('dashboard.today'), value: 'today' }, { label: t('dashboard.yesterday'), value: 'yesterday' },
  { label: t('dashboard.days', { count: 7 }), value: '7d' }, { label: t('dashboard.days', { count: 14 }), value: '14d' },
  { label: t('dashboard.days', { count: 30 }), value: '30d' },
])
async function load(){loading.value=true;error.value='';try{data.value=(await api.get('/dashboard/summary',{params:filter.params})).data}catch(e){error.value=errorMessage(e)}finally{loading.value=false}}
watch(()=>[filter.applicationNamespace,filter.sourceId,filter.agentId,...filter.range],load);onMounted(load)
const errorTotal=computed(()=>(data.value?.systemErrors||0)+(data.value?.businessErrors||0))
</script>
<template>
  <div class="page">
    <PageHeader :title="t('dashboard.title')" :subtitle="t('dashboard.subtitle')" :loading="loading" @refresh="load" />
    <section class="quick-range-bar" :aria-label="t('dashboard.quickRange')">
      <div class="quick-range-options" role="group">
        <button v-for="item in quickRanges" :key="item.value" type="button"
          :class="{ active: filter.rangePreset === item.value }"
          :aria-pressed="filter.rangePreset === item.value"
          @click="filter.selectRangePreset(item.value)">{{ item.label }}</button>
      </div>
    </section>
    <div v-if="error" class="notice error">{{ error }}</div>
    <section class="metric-strip" v-loading="loading">
      <article><div class="metric-label"><Radio :size="15"/>{{ t('dashboard.totalAccess') }}</div><strong>{{ formatNumber(data?.totalAccess||0) }}</strong><span>{{ t('dashboard.periodTotal') }}</span></article>
      <article><div class="metric-label"><Gauge :size="15"/>{{ t('dashboard.averageRate') }}</div><strong>{{ formatNumber(data?.averagePerMinute||0) }}</strong><span>{{ t('dashboard.perMinute') }}</span></article>
      <article><div class="metric-label"><Layers3 :size="15"/>{{ t('dashboard.peakRate') }}</div><strong>{{ formatNumber(data?.peakPerMinute||0) }}</strong><span>{{ t('dashboard.highestMinute') }}</span></article>
      <article class="danger"><div class="metric-label"><CircleAlert :size="15"/>{{ t('dashboard.errorEvents') }}</div><strong>{{ formatNumber(errorTotal) }}</strong><span>{{ t('dashboard.systemBusiness',{system:data?.systemErrors||0,business:data?.businessErrors||0}) }}</span></article>
    </section>
    <section class="split-band">
      <div class="band-panel"><div class="section-heading"><div><h2>{{ t('dashboard.accessTrend') }}</h2><span>{{ t('dashboard.accessTrendHint') }}</span></div></div><TrendChart v-if="data?.accessTrend.length" :data="data.accessTrend"/><EmptyState v-else /></div>
      <div class="band-panel"><div class="section-heading"><div><h2>{{ t('dashboard.errorTrend') }}</h2><span>{{ t('dashboard.errorTrendHint') }}</span></div></div><TrendChart v-if="data?.errorTrend.length" :data="data.errorTrend" tone="danger"/><EmptyState v-else /></div>
    </section>
    <section class="split-band tables">
      <div class="band-panel"><div class="section-heading"><div><h2>{{ t('dashboard.topEndpoints') }}</h2><span>{{ t('dashboard.byAccess') }}</span></div><router-link to="/endpoints">{{ t('common.all') }}<ArrowUpRight :size="14"/></router-link></div>
        <div class="compact-list" v-if="data?.topEndpoints.length"><div v-for="(item,i) in data.topEndpoints" :key="item.id"><span class="rank">{{String(i+1).padStart(2,'0')}}</span><div class="truncate"><strong>{{item.uri}}</strong><span>{{item.service}}</span></div><b>{{formatNumber(item.totalCount)}}</b></div></div><EmptyState v-else />
      </div>
      <div class="band-panel"><div class="section-heading"><div><h2>{{ t('dashboard.topErrors') }}</h2><span>{{ t('dashboard.byOccurrence') }}</span></div><router-link to="/errors">{{ t('common.all') }}<ArrowUpRight :size="14"/></router-link></div>
        <div class="compact-list errors" v-if="data?.topErrors.length"><div v-for="item in data.topErrors" :key="item.id"><span class="severity" :class="item.category.toLowerCase()"></span><div class="truncate"><strong>{{item.summary}}</strong><span>{{item.service}} · {{item.exceptionClass}}</span></div><b>{{formatNumber(item.occurrenceCount)}}</b></div></div><EmptyState v-else />
      </div>
    </section>
  </div>
</template>
