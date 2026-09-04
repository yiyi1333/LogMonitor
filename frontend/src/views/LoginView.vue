<script setup lang="ts">
import { Activity, ArrowRight, LockKeyhole } from 'lucide-vue-next'
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { errorMessage } from '../api'
import { useSessionStore } from '../stores/session'
import ThemeSwitcher from '../components/ThemeSwitcher.vue'
import LanguageSwitcher from '../components/LanguageSwitcher.vue'
const username=ref('admin'), password=ref(''), loading=ref(false), error=ref('')
const session=useSessionStore(), router=useRouter(), route=useRoute()
const { t } = useI18n()
const passwordChanged=computed(()=>route.query.passwordChanged==='1')
const sessionNotice=computed(()=>route.query.reason==='account-disabled'?t('auth.accountDisabled'):route.query.reason==='session-revoked'?t('auth.sessionRevoked'):'')
async function submit(){ loading.value=true;error.value='';try{const user=await session.login(username.value,password.value);await router.replace(user.mustChangePassword?'/change-password':String(route.query.redirect||'/dashboard'))}catch(e){error.value=errorMessage(e)}finally{loading.value=false}}
</script>
<template>
  <main class="login-page">
    <div class="login-preferences"><LanguageSwitcher/><ThemeSwitcher/></div>
    <section class="login-panel">
      <div class="login-brand"><span class="brand-mark large" aria-hidden="true"><img class="brand-logo-light" src="/brand/logmonitor-logo.png" alt=""><img class="brand-logo-dark" src="/brand/logmonitor-logo-dark.png" alt=""></span><div><strong>log monitor</strong><span>{{ t('auth.console') }}</span></div></div>
      <div class="login-heading"><Activity :size="18"/><span>{{ t('auth.internal') }}</span></div>
      <h1>{{ t('auth.loginTitle') }}</h1>
      <p v-if="passwordChanged" class="form-success">{{ t('auth.passwordChanged') }}</p>
      <p v-if="sessionNotice" class="form-session-notice">{{ sessionNotice }}</p>
      <form @submit.prevent="submit">
        <label>{{ t('auth.username') }}<el-input v-model="username" autocomplete="username" size="large" /></label>
        <label>{{ t('auth.password') }}<el-input v-model="password" type="password" show-password autocomplete="current-password" size="large" @keyup.enter="submit" /></label>
        <p v-if="error" class="form-error">{{ error }}</p>
        <button class="primary-button" type="submit" :disabled="loading"><LockKeyhole :size="16"/><span>{{ loading?t('auth.signingIn'):t('auth.signIn') }}</span><ArrowRight :size="16"/></button>
      </form>
    </section>
  </main>
</template>
