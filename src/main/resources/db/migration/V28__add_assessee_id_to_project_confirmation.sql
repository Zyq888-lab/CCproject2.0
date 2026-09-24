-- ============================================================
-- V28: 项目确认表人员级粒度 — PostgreSQL
-- Track B 修复：总裁确认单元从 (period_id, project_code) 细化为
--   (period_id, project_code, assessee_id)，支持单人员退回不影响同项目其他人。
-- ============================================================

-- 1. 新增 assessee_id 列（人员级退回粒度）
ALTER TABLE project_confirmation ADD COLUMN IF NOT EXISTS assessee_id VARCHAR(64);

-- 2. 历史项目级行回填：旧行以 project_code 作为哨兵 assessee_id
--    （历史 COMPLETED 周期不再参与确认，仅需满足 NOT NULL 约束）
UPDATE project_confirmation SET assessee_id = project_code WHERE assessee_id IS NULL;

-- 3. assessee_id 设为 NOT NULL（每行必属一名员工）
ALTER TABLE project_confirmation ALTER COLUMN assessee_id SET NOT NULL;

-- 4. 唯一键从 (period_id, project_code) 改为 (period_id, project_code, assessee_id)
ALTER TABLE project_confirmation DROP CONSTRAINT IF EXISTS uk_project_confirmation;
ALTER TABLE project_confirmation ADD CONSTRAINT uk_project_confirmation UNIQUE (period_id, project_code, assessee_id);
