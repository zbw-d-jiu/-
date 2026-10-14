import Vue from 'vue'
import VueRouter from 'vue-router'
import { session, refreshUser } from './api.js'
Vue.use(VueRouter)
// 根组件按路由渲染对应功能；占位出口不实例化第二份业务组件。
const outlet = { render: h => h('span') }
// History路由使用站点根路径；部署必须让非/api页面路径回退index.html，刷新/login等路径不会404。
export const router = new VueRouter({ mode: 'history', base: import.meta.env.BASE_URL, routes: [
  { path: '/login', component: outlet, meta: { public: true } },
  { path: '/register', component: outlet, meta: { public: true } },
  ...['rooms', 'orders', 'violations', 'profile'].map(name => ({ path: `/${name}`, component: outlet, meta: { role: 'STUDENT' } })),
  ...['dashboard', 'rooms', 'orders', 'users', 'violations', 'profile'].map(name => ({ path: `/admin/${name}`, component: outlet, meta: { role: 'ADMIN' } })),
  { path: '*', redirect: '/rooms' }
] })
/** 每次受保护导航核验当前角色；有 token 无 user 时先 GET/me，异常回登录。 */
router.beforeEach(async (to, from, next) => {
  if (to.meta.public) return next()
  if (!session.token) return next('/login')
  try { if (!session.user) await refreshUser() } catch { return next('/login') }
  if (to.meta.role !== session.user.role) return next(session.user.role === 'ADMIN' ? '/admin/dashboard' : '/rooms')
  next()
})
