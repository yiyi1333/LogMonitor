import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles.css'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import { useLocaleStore } from './stores/locale'
import { useThemeStore } from './stores/theme'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
useThemeStore(pinia).initialize()
useLocaleStore(pinia).initialize()
app.use(i18n).use(router).use(ElementPlus).mount('#app')
