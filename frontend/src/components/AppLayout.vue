<script setup lang="ts">
import { Activity, Braces, CircleAlert, Database, KeyRound, LogOut, Menu, Server, Settings, UsersRound } from 'lucide-vue-next'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useSessionStore } from '../stores/session'
import ThemeSwitcher from './ThemeSwitcher.vue'
import LanguageSwitcher from './LanguageSwitcher.vue'
const route = useRoute()
const session = useSessionStore()
const mobileOpen = ref(false)
const { t } = useI18n()
const nav = computed(() => [
  { to: '/dashboard', label: t('nav.dashboard'), icon: Activity },
  { to: '/endpoints', label: t('nav.endpoints'), icon: Braces },
  { to: '/errors', label: t('nav.errors'), icon: CircleAlert },
  { to: '/sources', label: t('nav.sources'), icon: Database },
  { to: '/agents', label: t('nav.agents'), icon: Server },
  { to: '/settings', label: t('nav.settings'), icon: Settings },
  ...(session.role === 'ROOT' ? [{ to: '/users', label: t('nav.users'), icon: UsersRound }] : []),
])
</script>

<template>
  <div class="shell">
    <aside class="sidebar" :class="{ open: mobileOpen }">
      <div class="brand"><span class="brand-mark" aria-hidden="true"><img class="brand-logo-light" src="/brand/logmonitor-logo.png" alt=""><img class="brand-logo-dark" src="/brand/logmonitor-logo-dark.png" alt=""></span><span>log monitor</span></div>
      <nav>
        <router-link v-for="item in nav" :key="item.to" :to="item.to" :class="{ active: route.path === item.to || (item.to === '/errors' && route.path.startsWith('/errors/')) }" @click="mobileOpen=false">
          <component :is="item.icon" :size="17"/><span>{{ item.label }}</span>
        </router-link>
      </nav>
      <div class="account">
        <div class="account-identity"><span class="status-dot"></span><span>{{ session.username }}</span></div>
        <div class="account-actions">
          <LanguageSwitcher compact />
          <ThemeSwitcher />
          <router-link class="icon-button" to="/change-password" :title="t('nav.changePassword')"><KeyRound :size="16"/></router-link>
          <button class="icon-button" :title="t('nav.logout')" @click="session.logout"><LogOut :size="16"/></button>
        </div>
      </div>
    </aside>
    <header class="mobile-header">
      <button class="icon-button" :title="t('nav.open')" @click="mobileOpen=!mobileOpen"><Menu :size="19"/></button>
      <div class="brand"><span class="brand-mark" aria-hidden="true"><img class="brand-logo-light" src="/brand/logmonitor-logo.png" alt=""><img class="brand-logo-dark" src="/brand/logmonitor-logo-dark.png" alt=""></span><span>log monitor</span></div>
      <LanguageSwitcher compact class="mobile-language-switcher" />
      <ThemeSwitcher class="mobile-theme-switcher" />
    </header>
    <main class="workspace"><router-view /></main>
  </div>
</template>
