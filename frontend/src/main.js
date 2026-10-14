import Vue from 'vue'
import ElementUI from 'element-ui'
import 'element-ui/lib/theme-chalk/index.css'
import App from './App.vue'
import { router } from './router.js'
import './style.css'
// 注册 Vue2 组件库和角色路由；权限最终由服务端复核，前端守卫用于防止错误导航。
Vue.use(ElementUI)
Vue.config.productionTip = false
new Vue({ router, render: h => h(App) }).$mount('#app')
