-- DEV-T03：MySQL8初始结构；Flyway保存校验和及版本，只运行一次，重复启动不重建或删除数据。
-- 原需求四业务表扩展会话与追加审计。仅取消释放预约；应用使用用户→房间→订单悲观锁防重叠。
CREATE TABLE campus_user (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '账号主键',
 stu_no VARCHAR(30) NOT NULL COMMENT '唯一学号/受控管理员登录号',
 user_name VARCHAR(40) NOT NULL COMMENT '姓名',
 password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt密码哈希，禁止对外返回',
 role VARCHAR(10) NOT NULL COMMENT '服务端角色STUDENT/ADMIN',
 violate_count INT NOT NULL DEFAULT 0 COMMENT '未撤销违规计数',
 is_ban BIT(1) NOT NULL DEFAULT b'0' COMMENT '计数至少3时限制新增预约',
 phone VARCHAR(20) COMMENT '联系电话',
 PRIMARY KEY(id), UNIQUE KEY uk_user_stu_no(stu_no),
 CONSTRAINT ck_user_role CHECK(role IN ('STUDENT','ADMIN')),
 CONSTRAINT ck_user_count CHECK(violate_count>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户及处罚快照';

CREATE TABLE study_room (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '房间主键',
 room_no VARCHAR(30) NOT NULL COMMENT '唯一房间编号',
 floor VARCHAR(30) NOT NULL COMMENT '楼层展示',
 capacity INT NOT NULL COMMENT '容量仅展示，不是并发名额',
 status VARCHAR(10) NOT NULL COMMENT 'OPEN开放/CLOSED关闭',
 remark VARCHAR(200) COMMENT '备注',
 deleted BIT(1) NOT NULL DEFAULT b'0' COMMENT '软删除标记，保留历史关联',
 PRIMARY KEY(id), UNIQUE KEY uk_room_no(room_no),
 CONSTRAINT ck_room_capacity CHECK(capacity>0),
 CONSTRAINT ck_room_status CHECK(status IN ('OPEN','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='整间独占房间资源';

CREATE TABLE booking (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '订单主键',
 user_id BIGINT NOT NULL COMMENT '学生外键',
 room_id BIGINT NOT NULL COMMENT '房间外键',
 order_date DATE NOT NULL COMMENT '上海预约归属日期',
 start_time TIME(6) NOT NULL COMMENT '半开区间开始HH:mm',
 end_time TIME(6) NOT NULL COMMENT '半开区间结束HH:mm',
 status VARCHAR(12) NOT NULL COMMENT 'RESERVED/COMPLETED/CANCELLED/NO_SHOW',
 attendance VARCHAR(10) NOT NULL COMMENT 'UNKNOWN/ARRIVED/LATE/NO_SHOW管理员证据',
 arrived_at DATETIME(6) COMMENT '管理员确认到场真实时间',
 completed_at DATETIME(6) COMMENT '管理员确认完成真实时间',
 created_at DATETIME(6) NOT NULL COMMENT '创建真实时间',
 PRIMARY KEY(id), KEY ix_booking_user_window(user_id,order_date,status,start_time,end_time),
 KEY ix_booking_room_window(room_id,order_date,status,start_time,end_time),
 CONSTRAINT fk_booking_user FOREIGN KEY(user_id) REFERENCES campus_user(id),
 CONSTRAINT fk_booking_room FOREIGN KEY(room_id) REFERENCES study_room(id),
 CONSTRAINT ck_booking_window CHECK(start_time<end_time AND start_time>='08:00:00' AND end_time<='22:00:00'),
 CONSTRAINT ck_booking_status CHECK(status IN ('RESERVED','COMPLETED','CANCELLED','NO_SHOW')),
 CONSTRAINT ck_booking_attendance CHECK(attendance IN ('UNKNOWN','ARRIVED','LATE','NO_SHOW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='历史预约，只有取消释放原时段';

CREATE TABLE violation_event (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '违规事件主键',
 user_id BIGINT NOT NULL COMMENT '学生外键',
 order_id BIGINT NOT NULL COMMENT '订单外键，可有多条已撤销历史',
 active_order_id BIGINT COMMENT '活动违规等于order_id，撤销为null，实现每单至多1活动违规',
 type VARCHAR(10) NOT NULL COMMENT 'LATE迟到/NO_SHOW爽约',
 reason VARCHAR(500) NOT NULL COMMENT '原始证据原因，不被撤销说明覆盖',
 occurred_at DATETIME(6) NOT NULL COMMENT '首次确认真实时间',
 revoked BIT(1) NOT NULL DEFAULT b'0' COMMENT '纠错撤销标记',
 revoked_at DATETIME(6) COMMENT '撤销真实时间',
 revoke_reason VARCHAR(500) COMMENT '撤销依据',
 PRIMARY KEY(id), UNIQUE KEY uk_violation_active(active_order_id), KEY ix_violation_user(user_id,revoked),
 CONSTRAINT fk_violation_user FOREIGN KEY(user_id) REFERENCES campus_user(id),
 CONSTRAINT fk_violation_order FOREIGN KEY(order_id) REFERENCES booking(id),
 CONSTRAINT fk_violation_active FOREIGN KEY(active_order_id) REFERENCES booking(id),
 CONSTRAINT ck_violation_type CHECK(type IN ('LATE','NO_SHOW')),
 CONSTRAINT ck_violation_active CHECK((revoked=b'0' AND active_order_id IS NOT NULL AND active_order_id=order_id) OR (revoked=b'1' AND active_order_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='违规证据与保留撤销历史';

CREATE TABLE login_session (
 token_hash VARCHAR(64) NOT NULL COMMENT '随机Bearer令牌的SHA256摘要，非明文令牌',
 user_id BIGINT NOT NULL COMMENT '用户外键',
 expires_at DATETIME(6) NOT NULL COMMENT '16小时到期时间',
 PRIMARY KEY(token_hash), KEY ix_session_expiry(expires_at),
 CONSTRAINT fk_session_user FOREIGN KEY(user_id) REFERENCES campus_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可注销服务端会话';

CREATE TABLE operation_audit (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '审计主键',
 actor_id BIGINT NOT NULL COMMENT '操作者用户ID',
 action VARCHAR(40) NOT NULL COMMENT '管理或取消动作',
 target_ref VARCHAR(80) NOT NULL COMMENT '目标类型和ID',
 reason VARCHAR(500) NOT NULL COMMENT '原因及状态变化说明',
 occurred_at DATETIME(6) NOT NULL COMMENT '操作真实时间',
 PRIMARY KEY(id), KEY ix_audit_target(target_ref,occurred_at),
 CONSTRAINT fk_audit_actor FOREIGN KEY(actor_id) REFERENCES campus_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='追加审计，不物理删除历史';
