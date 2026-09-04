<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'

const route = useRoute()
const { t } = useI18n()
const active = computed(() => route.path.startsWith('/errors/logs') ? 'logs' : 'groups')
</script>

<template>
  <div class="error-module">
    <nav class="error-subnav" :aria-label="t('errors.moduleNavigation')">
      <router-link to="/errors/groups" :class="{ active: active === 'groups' }">{{ t('errors.groupTab') }}</router-link>
      <router-link to="/errors/logs" :class="{ active: active === 'logs' }">{{ t('errors.streamTab') }}</router-link>
    </nav>
    <router-view/>
  </div>
</template>
