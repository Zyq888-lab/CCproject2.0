-- ============================================================
-- V25: 差异报告表新增项目阶段列 — PostgreSQL
-- 背景：差异报告卡片需展示员工姓名/项目名并提供跳转。NO_ASSESSOR/NO_PRIMARY_ASSESSOR
--   差异带具体项目+阶段，落库后可按 (project_code, project_stage) 精确关联 project 表。
-- ============================================================

ALTER TABLE discrepancy_log
    ADD COLUMN IF NOT EXISTS project_stage VARCHAR(8) NULL;

COMMENT ON COLUMN discrepancy_log.project_stage IS '关联项目阶段，缺岗位配置/无直属上级场景为 NULL';
