<script setup lang="ts">
import { computed } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import type { TimePoint } from '../types'
import { useThemeStore } from '../stores/theme'
import { formatChartTime } from '../format'
import { useLocaleStore } from '../stores/locale'
use([CanvasRenderer, LineChart, GridComponent, TooltipComponent])
const props = withDefaults(defineProps<{ data: TimePoint[]; tone?: 'primary' | 'danger' }>(), { tone: 'primary' })
const theme = useThemeStore()
const locale = useLocaleStore()
const palette = computed(() => theme.resolvedTheme === 'dark' ? {
  tooltipBackground: '#20231f', tooltipBorder: '#3b4039', tooltipText: '#eef0e9',
  axis: '#353a34', axisText: '#7f887b', grid: '#252924', line: '#b4d455', danger: '#e06c62',
} : {
  tooltipBackground: '#ffffff', tooltipBorder: '#cfd6ce', tooltipText: '#202720',
  axis: '#c8d0c7', axisText: '#667165', grid: '#e2e7e1', line: '#668a20', danger: '#c34f47',
})
const seriesColor = computed(() => props.tone === 'danger' ? palette.value.danger : palette.value.line)
const option = computed(() => ({
  locale: locale.current,
  animationDuration: 350,
  grid: { left: 8, right: 10, top: 12, bottom: 22, containLabel: true },
  tooltip: { trigger: 'axis', backgroundColor: palette.value.tooltipBackground, borderColor: palette.value.tooltipBorder, textStyle: { color: palette.value.tooltipText, fontSize: 12 } },
  xAxis: { type: 'category', boundaryGap: false, data: props.data.map(x => formatChartTime(x.time)), axisLine: { lineStyle: { color:palette.value.axis } }, axisLabel: { color:palette.value.axisText, hideOverlap:true } },
  yAxis: { type:'value', minInterval:1, splitLine:{ lineStyle:{ color:palette.value.grid } }, axisLabel:{ color:palette.value.axisText } },
  series: [{ type:'line', data:props.data.map(x=>x.value), showSymbol:false, smooth:0.2, lineStyle:{ width:2,color:seriesColor.value }, areaStyle:{ color:seriesColor.value, opacity:0.08 } }],
}))
</script>
<template><VChart class="trend-chart" :option="option" autoresize /></template>
