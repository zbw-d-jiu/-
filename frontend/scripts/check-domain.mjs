import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { localDate, orderMoment, passwordError } from '../src/time.js'

// 业务纯函数边界自检：固定时钟覆盖上海跨日/跨月与UTF-8上限，独立于后端服务和真实账号。
const fixed = new Date('2026-10-14T16:01:00Z')
assert.equal(localDate(0, fixed), '2026-10-15')
assert.equal(localDate(7, new Date('2026-10-29T16:01:00Z')), '2026-11-06')
assert.equal(orderMoment('2026-10-15', '09:00').getTime(), Date.UTC(2026, 9, 15, 1))
assert.ok(passwordError('abcdefg'))
assert.equal(passwordError('abcdefgh'), '')
assert.equal(passwordError('汉'.repeat(24)), '')
assert.ok(passwordError('汉'.repeat(25)).includes('72 字节'))
assert.equal(passwordError('a'.repeat(72)), '')
assert.ok(passwordError('a'.repeat(73)))

// 子进程分别采用UTC及洛杉矶时区，验证日期组件本地年月日不会错误越界；不修改调用者系统时区。
const helperUrl = new URL('../src/time.js', import.meta.url).href
const scenario = `import assert from 'node:assert/strict';
import { localDate, orderMoment, isBookingDate } from ${JSON.stringify(helperUrl)};
const now = new Date('2026-10-14T16:01:00Z');
assert.equal(localDate(0, now), '2026-10-15');
assert.equal(isBookingDate(new Date(2026, 9, 14), now), false);
assert.equal(isBookingDate(new Date(2026, 9, 15), now), true);
assert.equal(isBookingDate(new Date(2026, 9, 22), now), true);
assert.equal(isBookingDate(new Date(2026, 9, 23), now), false);
assert.equal(orderMoment('2026-10-15', '09:00').getTime(), Date.UTC(2026, 9, 15, 1));`
for (const zone of ['UTC', 'America/Los_Angeles', 'Asia/Shanghai']) {
  execFileSync(process.execPath, ['--input-type=module', '-e', scenario], { env: { ...process.env, TZ: zone }, stdio: 'pipe' })
  console.log(`业务日期边界通过：${zone}`)
}
console.log('密码 UTF-8 边界通过：24 汉字接受、25 汉字拒绝')
