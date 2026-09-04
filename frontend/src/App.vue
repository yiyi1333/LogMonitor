<script setup lang="ts">
import { useLocaleStore } from './stores/locale'
const locale = useLocaleStore()
</script>

<template><el-config-provider :locale="locale.elementLocale"><router-view /></el-config-provider></template>
