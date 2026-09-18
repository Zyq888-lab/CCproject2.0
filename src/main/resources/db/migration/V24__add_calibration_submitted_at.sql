-- ============================================================
-- V24: 考核周期表新增 PD 提交校准时间 — PostgreSQL
-- 背景：补 PD「提交给总裁确认」信号——PD 校准完成后点击「提交校准」写当前时间，
--   NULL 表示尚未提交，供总裁确认页/周期监控页展示「PD 已提交/尚未提交」提示。
-- ============================================================

ALTER TABLE assessment_period
    ADD COLUMN IF NOT EXISTS calibration_submitted_at TIMESTAMP NULL;

COMMENT ON COLUMN assessment_period.calibration_submitted_at IS 'PD 提交校准时间（NULL=尚未提交）';
