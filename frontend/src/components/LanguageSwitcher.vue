<script setup lang="ts">
import { Check } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import { localeOptions, type SupportedLocale } from '../i18n/locales'
import { useLocaleStore } from '../stores/locale'

const locale = useLocaleStore()
const { t } = useI18n()
withDefaults(defineProps<{ compact?: boolean }>(), { compact: false })

function selectLanguage(command: string | number | object) {
  if (typeof command === 'string' && localeOptions.some(option => option.code === command)) {
    locale.setLocale(command as SupportedLocale)
  }
}
</script>

<template>
  <el-dropdown trigger="click" placement="bottom-end" @command="selectLanguage">
    <button class="language-trigger" :class="{ compact }" type="button" :title="t('language.switch')" :aria-label="t('language.switch')">
      <img :src="locale.option.flag" alt=""/><span v-if="!compact">{{ locale.option.label }}</span>
    </button>
    <template #dropdown>
      <el-dropdown-menu class="language-menu">
        <el-dropdown-item v-for="option in localeOptions" :key="option.code" :command="option.code" :class="{ selected: locale.current === option.code }">
          <img :src="option.flag" alt=""/><span>{{ option.label }}</span><Check v-if="locale.current === option.code" :size="14"/>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>
