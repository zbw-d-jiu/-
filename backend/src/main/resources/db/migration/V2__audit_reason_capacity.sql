-- DEV-T03/T07：保留完整最多500字符管理员理由和追加的前后状态说明，不截断真实证据。
-- 仅扩容非破坏性字段；V1已在合成MySQL应用，按Flyway规则追加迁移而不改历史校验和。
ALTER TABLE operation_audit MODIFY reason VARCHAR(1000) NOT NULL COMMENT '完整操作原因及前后状态变化说明';
