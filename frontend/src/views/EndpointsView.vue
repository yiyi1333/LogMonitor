<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Search, TrendingUp, X } from 'lucide-vue-next'
import PageHeader from '../components/PageHeader.vue';import TrendChart from '../components/TrendChart.vue';import EmptyState from '../components/EmptyState.vue'
import { api,errorMessage } from '../api';import { useFilterStore } from '../stores/filter';import type { EndpointRow,PageResult,TimePoint } from '../types'
const filter=useFilterStore(),rows=ref<EndpointRow[]>([]),total=ref(0),page=ref(1),pageSize=ref(20),keyword=ref(''),loading=ref(false),error=ref(''),selected=ref<EndpointRow>(),trend=ref<TimePoint[]>([]),drawer=ref(false)
const { t } = useI18n()
const rate=(value:number)=>Number(value).toFixed(2)
async function load(){loading.value=true;error.value='';try{const d:PageResult<EndpointRow>=(await api.get('/endpoints',{params:{...filter.params,keyword:keyword.value||undefined,page:page.value,pageSize:pageSize.value}})).data;rows.value=d.items;total.value=d.total}catch(e){error.value=errorMessage(e)}finally{loading.value=false}}
async function open(row:EndpointRow){selected.value=row;drawer.value=true;trend.value=(await api.get(`/endpoints/${row.id}/trend`,{params:filter.params})).data}
watch(()=>[filter.applicationNamespace,filter.sourceId,filter.agentId,...filter.range],()=>{page.value=1;load()});onMounted(load)
</script>
<template><div class="page"><PageHeader :title="t('endpoints.title')" :subtitle="t('endpoints.subtitle')" :loading="loading" @refresh="load"/>
  <div class="tool-row"><el-input v-model="keyword" :placeholder="t('endpoints.search')" clearable @keyup.enter="page=1;load()"><template #prefix><Search :size="15"/></template></el-input><button class="secondary-button" @click="page=1;load()">{{ t('common.query') }}</button><span>{{ t('endpoints.count',{count:total}) }}</span></div>
  <div v-if="error" class="notice error">{{error}}</div>
  <section class="data-surface" v-loading="loading"><el-table :data="rows" height="calc(100vh - 245px)" @row-click="open">
    <el-table-column prop="service" :label="t('endpoints.service')" width="120"/><el-table-column prop="uri" label="URI" min-width="420"><template #default="s"><code>{{s.row.uri}}</code></template></el-table-column>
    <el-table-column prop="totalCount" :label="t('endpoints.access')" width="110" sortable/><el-table-column prop="averagePerMinute" :label="t('endpoints.average')" width="120" sortable><template #default="s">{{rate(s.row.averagePerMinute)}}</template></el-table-column><el-table-column prop="peakPerMinute" :label="t('endpoints.peak')" width="120" sortable/>
    <el-table-column prop="errorCount" :label="t('endpoints.errors')" width="110"><template #default="s"><span :class="{'text-danger':s.row.errorCount}">{{s.row.errorCount}}</span></template></el-table-column>
    <el-table-column width="56"><template #default><TrendingUp :size="16" class="muted"/></template></el-table-column><template #empty><EmptyState/></template>
  </el-table><el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="total" layout="total, prev, pager, next" @current-change="load"/></section>
  <el-drawer v-model="drawer" size="min(720px, 94vw)" :with-header="false"><div class="drawer-header"><div><span class="eyebrow">{{ t('endpoints.trend') }}</span><h2>{{selected?.uri}}</h2><p>{{selected?.service}} · {{ t('endpoints.method') }}</p></div><button class="icon-button" :title="t('common.close')" @click="drawer=false"><X :size="18"/></button></div><div class="drawer-metrics"><span><b>{{selected?.totalCount}}</b>{{ t('endpoints.access') }}</span><span><b>{{rate(selected?.averagePerMinute||0)}}</b>{{ t('endpoints.average') }}</span><span><b>{{selected?.peakPerMinute}}</b>{{ t('endpoints.peak') }}</span></div><TrendChart v-if="trend.length" :data="trend"/><EmptyState v-else/></el-drawer>
</div></template>
