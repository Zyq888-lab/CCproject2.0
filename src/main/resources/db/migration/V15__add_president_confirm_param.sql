-- ============================================================
-- V15: 新增 NEED_PRESIDENT_CONFIRM 系统参数 — PostgreSQL
-- 对应 Phase 2.1 总裁确认开关（Phase 2.0 预置）
-- ============================================================

INSERT INTO system_param (param_key, param_value, description)
VALUES ('NEED_PRESIDENT_CONFIRM', 'true', '是否需要总裁确认环节（true/false）');
