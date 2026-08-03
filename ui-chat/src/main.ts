import { createApp } from 'vue'
import { Quasar } from 'quasar'
import 'quasar/src/css/index.sass'
import quasarOptions from '../quasar.config'
import App from './App.vue'
import './style.css'

createApp(App).use(Quasar, quasarOptions).mount('#app')
