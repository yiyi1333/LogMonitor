<script setup lang="ts">
import { Check, Monitor, Moon, Sun } from 'lucide-vue-next'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useThemeStore, type ThemePreference } from '../stores/theme'

const theme = useThemeStore()
const { t } = useI18n()
const options = computed<{ value: ThemePreference; label: string; icon: typeof Monitor }[]>(() => [
  { value: 'system', label: t('theme.system'), icon: Monitor },
  { value: 'light', label: t('theme.light'), icon: Sun },
  { value: 'dark', label: t('theme.dark'), icon: Moon },
])
const activeLabel = computed(() => options.value.find(option => option.value === theme.preference)?.label || t('theme.system'))
const activeIcon = computed(() => theme.resolvedTheme === 'dark' ? Moon : Sun)

function selectTheme(command: string | number | object) {
  if (command === 'system' || command === 'light' || command === 'dark') theme.setPreference(command)
}
</script>

<template>
  <el-dropdown trigger="click" placement="bottom-end" @command="selectTheme">
    <button class="icon-button theme-trigger" type="button" :title="`${t('theme.switch')}: ${activeLabel}`" :aria-label="t('theme.switch')">
      <component :is="activeIcon" :size="16" />
    </button>
    <template #dropdown>
      <el-dropdown-menu class="theme-menu">
        <el-dropdown-item v-for="option in options" :key="option.value" :command="option.value" :class="{ selected: theme.preference === option.value }">
          <component :is="option.icon" :size="15" />
          <span>{{ option.label }}</span>
          <Check v-if="theme.preference === option.value" class="theme-check" :size="14" />
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>
