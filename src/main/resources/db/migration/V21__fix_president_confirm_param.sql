-- ============================================================
-- V21: 修复 NEED_PRESIDENT_CONFIRM 系统参数 — PostgreSQL
-- -----------------------------------------------------------------
-- 背景：V15 用 INSERT ... ON CONFLICT (param_key) DO NOTHING，无法覆盖
--   V3 已种入的 'false'（ON CONFLICT DO NOTHING 对已存在行静默跳过，
--   导致总裁确认开关始终为 false，Phase 2.1 启用总裁确认后失效）。
-- 做法：直接 UPDATE 为 'true'，幂等（重复执行不影响结果）。
-- ============================================================

UPDATE system_param SET param_value = 'true'
WHERE param_key = 'NEED_PRESIDENT_CONFIRM';
