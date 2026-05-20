-- ============================================================
-- V4: 为员工表增加乐观锁version字段 — PostgreSQL
-- 对应 /review T6 发现：updateEmployee read-then-write 竞态条件
-- ============================================================

ALTER TABLE employee ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN employee.version IS '乐观锁版本号';
