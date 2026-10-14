// 业务日期统一按Asia/Shanghai（UTC+8，无夏令时）计算；浏览器本地时区只用于日期组件日历外观。
const SHANGHAI_OFFSET = 8 * 60 * 60 * 1000
const DAY = 24 * 60 * 60 * 1000

/** 输入固定时钟和自然日偏移，输出上海yyyy-MM-dd；不依赖客户端时区，服务器仍最终校验时钟。 */
export function localDate(offset = 0, now = new Date()) {
  const date = new Date(now.getTime() + SHANGHAI_OFFSET + offset * DAY)
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}-${String(date.getUTCDate()).padStart(2, '0')}`
}

/** 输入服务端订单日期及时分，输出明确+08:00的绝对时间；用于搜索、取消、到场按钮时间边界。 */
export function orderMoment(date, time) { return new Date(`${date}T${time}+08:00`) }

/** 日期组件传入本地日历Date，应取其年月日再比较上海日期，不能比较其本地零点的绝对时间。 */
export function pickerDate(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

/** 输入日期组件日历Date及固定时钟；今天至+days含两端。days与后端bookingDays须同步维护。 */
export function isBookingDate(date, now = new Date(), days = 7) {
  const value = pickerDate(date)
  return value >= localDate(0, now) && value <= localDate(days, now)
}

/** BCrypt输入上限按UTF-8字节而非字符；输出空串表示通过，否则返回中文错误，不截断密码。 */
export function passwordError(value) {
  if (!value || Array.from(value).length < 8) return '密码至少为 8 个字符'
  if (new TextEncoder().encode(value).length > 72) return '密码 UTF-8 编码最多 72 字节（汉字通常为 3 字节）'
  return ''
}
