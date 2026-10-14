-- 最新用户规则：每间固定30人共享容量，一名学生每个预约占1个名额；覆盖旧版整间独占工程选择。
-- 非破坏迁移：统一新旧（含软删除历史）房间容量，订单、违规、审计不删除不改写。
-- V1/V2已经应用，保留历史校验和，本次仅追加V3；应用容量检查使用用户→房间锁与时段事件峰值。
UPDATE study_room SET capacity = 30 WHERE capacity <> 30;
ALTER TABLE study_room MODIFY capacity INT NOT NULL DEFAULT 30 COMMENT '固定30人共享容量，每个预约占1个名额';
ALTER TABLE study_room ADD CONSTRAINT ck_room_fixed_capacity CHECK (capacity = 30);
