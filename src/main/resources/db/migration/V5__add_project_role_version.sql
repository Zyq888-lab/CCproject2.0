-- ============================================================
-- V5: 为项目角色表增加乐观锁version字段 — PostgreSQL
-- 对应 T8 要求：修改角色时使用乐观锁防止并发覆盖
-- ============================================================

ALTER TABLE project_role ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN project_role.version IS '乐观锁版本号';
