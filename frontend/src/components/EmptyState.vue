<script setup lang="ts">
import { Inbox } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'

defineProps<{ title?: string; text?: string }>()
const { t } = useI18n()
</script>
<template><div class="empty-state"><Inbox :size="22"/><strong>{{ title || t('empty.title') }}</strong><span>{{ text || t('empty.text') }}</span></div></template>
