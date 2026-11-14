import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import IconSvg from './components/IconSvg.vue'

const app = createApp(App)

app.component('IconSvg', IconSvg)
app.use(router)

app.mount('#app')
