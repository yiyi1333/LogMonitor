<script setup lang="ts">
import { ArrowLeft, ArrowRight, KeyRound } from 'lucide-vue-next'
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { api, errorMessage } from '../api'
import ThemeSwitcher from '../components/ThemeSwitcher.vue'
import LanguageSwitcher from '../components/LanguageSwitcher.vue'
import { useSessionStore } from '../stores/session'

const session = useSessionStore()
const router = useRouter()
const { t } = useI18n()
const form = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const loading = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  if (form.newPassword.length < 8 || form.newPassword.length > 64) {
    error.value = t('password.length')
    return
  }
  if (form.newPassword !== form.confirmPassword) {
    error.value = t('password.mismatch')
    return
  }
  loading.value = true
  try {
    await api.post('/auth/password', { currentPassword: form.currentPassword, newPassword: form.newPassword })
    session.clear()
    await router.replace({ path: '/login', query: { passwordChanged: '1' } })
  } catch (reason) {
    error.value = errorMessage(reason)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <div class="login-preferences"><LanguageSwitcher/><ThemeSwitcher/></div>
    <section class="login-panel password-panel">
      <div class="login-brand"><span class="brand-mark large">LM</span><div><strong>log monitor</strong><span>{{ t('auth.console') }}</span></div></div>
      <div class="login-heading"><KeyRound :size="18"/><span>{{ session.mustChangePassword ? t('password.firstLogin') : t('password.security') }}</span></div>
      <h1>{{ t('password.title') }}</h1>
      <form @submit.prevent="submit">
        <label>{{ t('password.current') }}<el-input v-model="form.currentPassword" type="password" show-password autocomplete="current-password" size="large"/></label>
        <label>{{ t('password.new') }}<el-input v-model="form.newPassword" type="password" show-password autocomplete="new-password" size="large"/></label>
        <label>{{ t('password.confirm') }}<el-input v-model="form.confirmPassword" type="password" show-password autocomplete="new-password" size="large"/></label>
        <p v-if="error" class="form-error">{{ error }}</p>
        <div class="password-actions">
          <button v-if="!session.mustChangePassword" class="secondary-button" type="button" @click="router.push('/dashboard')"><ArrowLeft :size="16"/>{{ t('password.back') }}</button>
          <button class="primary-button" type="submit" :disabled="loading"><KeyRound :size="16"/><span>{{ loading ? t('password.updating') : t('password.update') }}</span><ArrowRight :size="16"/></button>
        </div>
      </form>
    </section>
  </main>
</template>
