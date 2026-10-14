import Vue from 'vue'
// token 是唯一持久化认证材料，用户违规/封禁状态不落盘，避免旧权限污染刷新。
export const session = Vue.observable({ token: localStorage.getItem('study-token') || '', user: null })
/** 更新会话输入 token/user；退出清理缓存。用户对象仅在当前页面内存保留。 */
export function setSession(token, user) {
  session.token = token || ''; session.user = user || null
  if (token) localStorage.setItem('study-token', token)
  else localStorage.removeItem('study-token')
}
/** 调用统一 JSON 契约；非 2xx 使用服务端 message，401 清理会话并通知根组件导航。 */
export async function request(path, options = {}) {
  let response
  try {
    response = await fetch(`/api${path}`, { ...options, headers: { 'Content-Type': 'application/json', ...(session.token ? { Authorization: `Bearer ${session.token}` } : {}), ...options.headers }, body: options.body === undefined ? undefined : JSON.stringify(options.body) })
  } catch { throw new Error('无法连接服务，请确认后端已启动后重试。') }
  const body = await response.json().catch(() => null)
  if (response.status === 401) { setSession(); window.dispatchEvent(new Event('session-expired')) }
  if (!response.ok || !body || !body.success) {
    const error = new Error(body?.message || ({ 401: '登录已失效，请重新登录', 403: '您没有执行此操作的权限', 409: '数据状态已变化，请刷新后重试' }[response.status]) || '服务返回异常，请稍后重试')
    error.status = response.status; throw error
  }
  return body.data
}
/** 从服务端刷新当前用户，预约/取消/违规处理后调用；失败交调用者展示。 */
export async function refreshUser() { session.user = await request('/auth/me'); return session.user }
