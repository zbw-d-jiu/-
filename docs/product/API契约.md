# 校园自习室预约 API 契约

> 当前变更（2026-10-15 / CAP-T01）：容量、时段余量与人分钟统计遵循[30人名额变更规则与验收](30人名额变更规则与验收.md)。本文相关字段及算例已同步；原整间独占限制被替代，其他接口保持原约定。本轮独立测试仍待实际证据。

版本：0.03 / DEV-T01；日期：2026-10-14；责任角色：产品负责人（gpt-6-astra / high）。本文与[首版业务规则与验收](首版业务规则与验收.md)共同作为实现和测试输入；字段由主 agent 提供并已同步前后端，修改须先协调双方。文档存在不表示接口已经实现或测试通过。

## 1. 通用约定

- 路径以 `/api` 开头；JSON 请求与响应使用 UTF-8。除注册、登录、健康检查外均需 `Authorization: Bearer <token>`。
- 响应固定为 `{ "success": true, "data": ..., "message": "操作成功" }`；失败为 `{ "success": false, "data": null, "message": "可理解的中文原因" }`，不得暴露异常堆栈、SQL、密码或哈希。
- HTTP：成功200；字段/格式错误400；未登录或令牌无效401；角色/所有权/封禁预约403；资源不存在404；唯一性/预约冲突/非法状态转换409；内部错误500。前端以 `success` 和 HTTP 状态判断结果，不依赖中文文案精确匹配。
- ID 为正整数；布尔字段返回 JSON boolean；列表返回数组，无结果为 `[]`。首版不分页，适用合成课程演示规模；分页属于后续契约升级。
- 日期 `yyyy-MM-dd`，预约时分 `HH:mm`；事件时间采用 ISO 8601 日期时间，按 Asia/Shanghai 理解，可带 `+08:00`。服务器决定当前时间与事件时间，客户端不能回填历史到场或完成时间。
- 金额无关；统计率采用百分数数值0至100，保留至多2位小数，前端加 `%`；分钟可含小数，不逐条取整后求和。
- 管理员动作的 `reason` 为非空白说明；服务端保留操作者、动作、原因、发生时间和状态变化。令牌不写日志。客户端不得设置身份、违规计数、封禁标识或审计时间。
- 当前字段长度、密码校验上限等结构约束以实现中的校验和数据库约束同步公布，不能以数据库异常代替可理解的400反馈。`capacity` 必须为整数30，其他值或缺失返回400；`roomNo`、`userName`、`stuNo` 不得为空白；`phone` 允许空，非空时不得超过数据列长度。

## 2. 返回对象

| 对象 | 字段及含义 |
|---|---|
| User | `id`；`stuNo` 学号；`userName` 姓名；`phone` 联系方式；`role` 为 `STUDENT`/`ADMIN`；`violateCount` 未撤销违规数；`isBan` 是否达到封禁阈值。任何响应均无password/hash |
| Room | `id`；`roomNo` 房间号；`floor` 楼层；`capacity` 固定30名额；`status` 为 `OPEN`/`CLOSED`；`remark` 备注；`occupiedSeats` 为完整查询区间最大同时有效预约人数；`remainingSeats=30-occupiedSeats`；没有查询时段时两值为null。`available` 为OPEN且完整查询时段有余量，未提供时段时仅表示房间开放；快照不能当作最终预约承诺 |
| Order | `id`、`userId`、`userName`、`stuNo`、`roomId`、`roomNo`；`orderDate`、`startTime`、`endTime`；`status` 为 `RESERVED`/`COMPLETED`/`CANCELLED`/`NO_SHOW`；`attendance` 为 `UNKNOWN`/`ARRIVED`/`LATE`/`NO_SHOW`；`arrivedAt`、`completedAt` 无证据时为null；`createdAt` 创建时间 |
| Violation | `id`、`userId`、`userName`、`stuNo`、`orderId`；`type` 为 `LATE`/`NO_SHOW`；`reason` 判定说明；`occurredAt`；`revoked`；`revokedAt` 未撤销时为null。撤销原因另存审计，不用它覆盖原判定说明 |

`userName`、`stuNo`、`roomNo` 为便于展示的关联字段，不接受客户端用它们替代ID归属检查。房间软删除不得导致历史订单或违规查询丢失。

## 3. 认证与个人信息

| 方法/路径 | 权限 | 请求 | 成功data / 行为 |
|---|---|---|---|
| POST `/api/auth/register` | 公开 | `{stuNo,userName,password,phone}` | `{token,user}`；固定创建STUDENT；重复学号409；传入role/计数/封禁等额外字段绝不能改变权限 |
| POST `/api/auth/login` | 公开 | `{stuNo,password}` | `{token,user}`；账号或密码不匹配统一401；密码仅用于校验 |
| GET `/api/auth/me` | 已登录 | 无 | 当前User |
| PUT `/api/auth/me` | 已登录 | `{userName,phone}` | 更新后User；仅允许修改这两个字段 |
| POST `/api/auth/logout` | 已登录 | 无 | null；使当前令牌失效，客户端同时清除本地认证状态 |
| GET `/api/health` | 公开 | 无 | 健康状态对象；不公开连接串、配置、凭据 |

## 4. 房间

| 方法/路径 | 权限 | 请求/参数 | 成功data / 行为 |
|---|---|---|---|
| GET `/api/rooms` | 已登录 | 可选 `date,startTime,endTime`；筛选时三项一起提供 | Room[]；可展示关闭房间并标不可预约；不显示软删除房间。缺少部分时段参数400；完整参数按预约日期/时间规则验证 |
| GET `/api/rooms/{id}` | 已登录 | 正整数id | Room；软删除/不存在404 |
| POST `/api/admin/rooms` | ADMIN | `{roomNo,floor,capacity,status,remark}` | 新Room；房间号唯一，重复409 |
| PUT `/api/admin/rooms/{id}` | ADMIN | `{roomNo,floor,capacity,status,remark}` | 更新后Room；关闭前检查尚未结束的有效预约；改展示信息不改历史订单时间/归属 |
| DELETE `/api/admin/rooms/{id}` | ADMIN | 无 | null；软删除并审计；有尚未结束的有效预约409；不存在或已删除404 |

## 5. 预约与到场

| 方法/路径 | 权限 | 请求 | 成功data / 行为 |
|---|---|---|---|
| POST `/api/orders` | STUDENT | `{roomId,orderDate,startTime,endTime}` | 新Order；服务端取当前用户，不能代他人下单；被封禁403；关闭/冲突409；非法日期/时段400 |
| GET `/api/orders/me` | STUDENT | 无 | 本人全部状态Order[]，包含历史和取消订单 |
| POST `/api/orders/{id}/cancel` | STUDENT且本人 | 无 | 取消后Order；只允许开始前的RESERVED；重复取消不新增状态变更/审计；他人订单403 |
| GET `/api/admin/orders` | ADMIN | 无 | 全部Order[]，包含取消及历史 |
| POST `/api/admin/orders/{id}/attendance` | ADMIN | `{action,reason}`，action枚举见下 | 更新后Order；只允许开始后或恰好开始时；不允许客户端指定arrivedAt/completedAt |
| POST `/api/admin/orders/{id}/cancel` | ADMIN | `{reason}` | 取消后Order；允许开始后异常取消RESERVED；COMPLETED/NO_SHOW不能取消；原到场、违规证据保留 |

到场动作的原子效果：

| action | 允许的前置状态 | 成功状态及副作用 |
|---|---|---|
| ARRIVED | RESERVED/UNKNOWN，当前时间≥开始 | RESERVED/ARRIVED；记录服务器arrivedAt；不产生违规 |
| LATE | RESERVED/UNKNOWN，当前时间≥开始 | RESERVED/LATE；记录arrivedAt；创建一次活动迟到违规并重算用户计数/封禁 |
| NO_SHOW | RESERVED/UNKNOWN，当前时间≥开始 | NO_SHOW/NO_SHOW；创建一次活动爽约违规并重算用户计数/封禁；arrivedAt仍为null |
| COMPLETE | RESERVED且attendance为ARRIVED或LATE，当前时间≥结束 | COMPLETED；保留attendance/arrivedAt，记录completedAt，不再产生违规 |

相同动作在已达目标状态时幂等返回现状，不重写首次证据时间、不重复处罚。相互矛盾的动作409；已取消订单不能确认到场；UNKNOWN不能直接完成。首版没有按分钟自动计算迟到或自动爽约的计时任务；管理员需依据实际核实结果选择分类并说明原因。

## 6. 违规与用户管理

| 方法/路径 | 权限 | 请求 | 成功data / 行为 |
|---|---|---|---|
| GET `/api/violations/me` | STUDENT | 无 | 本人Violation[]，保留已撤销记录 |
| GET `/api/admin/users` | ADMIN | 无 | 学生User[]；不输出认证秘密 |
| GET `/api/admin/violations` | ADMIN | 无 | 全部Violation[] |
| POST `/api/admin/violations/{id}/revoke` | ADMIN | `{reason}` | 更新后Violation；重复撤销幂等；重算对应用户计数和isBan，保留撤销人/原因/时间审计 |

撤销LATE后保留arrivedAt并把attendance改为ARRIVED；订单若已完成仍为COMPLETED，若已取消仍为CANCELLED。撤销NO_SHOW后恢复RESERVED/UNKNOWN，清空无效到场/完成时间；过期订单不允许学生再取消或重新预约过去时段，等待管理员有证据后的处理。撤销是纠错，不能通过无依据重复撤销规避用户确认的≥3阈值。

## 7. 统计

GET `/api/admin/statistics?date=yyyy-MM-dd`，仅ADMIN；date必填且格式正确，允许查询历史日期。返回：

```json
{
  "success": true,
  "data": {
    "date": "2026-10-14",
    "totalRooms": 2,
    "totalOrders": 2,
    "totalViolations": 1,
    "bookingRate": 0.36,
    "usageRate": 0.12,
    "rooms": [
      {"roomId":1,"roomNo":"演示A101","reservedMinutes":120,"usedMinutes":60,"availableMinutes":25200,"bookingRate":0.48,"usageRate":0.24},
      {"roomId":2,"roomNo":"演示A102","reservedMinutes":60,"usedMinutes":0,"availableMinutes":25200,"bookingRate":0.24,"usageRate":0}
    ]
  },
  "message": "操作成功"
}
```

样例为人工构造的预期算例，不是执行结果。reservedMinutes、usedMinutes、availableMinutes当前均以人分钟计，默认每间840×30=25200人分钟；历史日期同样按当前30容量重算。精确筛选、裁剪及历史开放窗口限制沿用旧业务规则，容量分母及新预期以30人名额变更规则第4节为准。

## 8. 变更与交接

2026-10-14 / DEV-T01：读取AGENTS、PO角色配置、0.03派发与0.02原需求/验收矩阵，形成本文；命令为PowerShell Get-Content、rg，使用apply_patch写入。未执行接口测试；交开发实现、测试独立执行。需要修改路由、枚举、字段或状态边界时，先通知主 agent，由其同步前后端与测试，再更新本文版本记录。
