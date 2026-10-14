import Vue from 'vue'
import axios from 'axios'
// 全局Axios实例：开发默认走本地/api代理；生产值由公开VITE_API_BASE_URL在构建时注入，不存储凭据。
export const httpClient = axios.create({ baseURL: import.meta.env.VITE_API_BASE_URL || '/api', timeout: 20000 })
// token 是唯一持久化认证材料，用户违规/封禁状态不落盘，避免旧权限污染刷新。
export const session = Vue.observable({ token: localStorage.getItem('study-token') || '', user: null })
/** 更新会话输入 token/user；退出清理缓存。用户对象仅在当前页面内存保留。 */
export function setSession(token, user) {
  session.token = token || ''; session.user = user || null
  if (token) localStorage.setItem('study-token', token)
  else localStorage.removeItem('study-token')
}
/** 保持request(path,options)入口；body对象由Axios序列化，统一处理非2xx、业务失败及401事件。 */
export async function request(path, options = {}) {
  let response
  const { body: payload, headers, ...configuration } = options
  try {
    response = await httpClient.request({ ...configuration, url: path, data: payload, headers: { 'Content-Type': 'application/json', ...(session.token ? { Authorization: `Bearer ${session.token}` } : {}), ...headers }, validateStatus: () => true })
  } catch (failure) {
    // 无HTTP响应的网络/CORS/超时失败不伪造服务端状态；业务错误的status在下方按真实HTTP状态设置。
    throw new Error(failure.code === 'ECONNABORTED' ? '请求超时，请稍后重试。' : '无法连接服务，请确认网络及后端服务后重试。')
  }
  const body = response.data
  if (response.status === 401) { setSession(); window.dispatchEvent(new Event('session-expired')) }
  if (response.status < 200 || response.status >= 300 || !body || !body.success) {
    const error = new Error(body?.message || ({ 401: '登录已失效，请重新登录', 403: '您没有执行此操作的权限', 409: '数据状态已变化，请刷新后重试' }[response.status]) || '服务返回异常，请稍后重试')
    error.status = response.status; throw error
  }
  return body.data
}
/** 从服务端刷新当前用户，预约/取消/违规处理后调用；失败交调用者展示。 */
export async function refreshUser() { session.user = await request('/auth/me'); return session.user }
