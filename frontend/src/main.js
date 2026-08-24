import { createApp } from 'vue'
import App from './App.vue'
import TestMode from './TestMode.vue'
import './styles/global.css'

const isTest = new URLSearchParams(location.search).get('test') === '1'

if (isTest) {
  createApp(App)
    .component('TestMode', TestMode)
    .mount('#app')
} else {
  createApp(App).mount('#app')
}
