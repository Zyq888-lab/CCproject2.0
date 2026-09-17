-- ============================================================
-- V20: 角色主标记泛化 — is_primary_pd → is_primary
-- -----------------------------------------------------------------
-- 背景：原 is_primary_pd 语义是「项目级唯一 PD 负责人」，但业务要的是
--   「每 (项目, 阶段, 角色) 一个主」——同一考核人角色出现 2 人时，只有被标
--   为主的那一个审批/打分。字段语义从「PD 专用」泛化为「任意角色的唯一主审批人」。
-- 做法：
--   1) 列改名 is_primary_pd → is_primary（RENAME 会保留旧注释，故第 2 步重设）。
--   2) 更新注释为「该角色在该项目阶段的唯一主审批人」。
--   3) 部分唯一索引兜底：每 (项目, 阶段, 角色) 至多一个 is_primary=true 的未删除行；
--      单人未标主（is_primary=false）不进索引，不拦「1 人无需标主」。
-- ============================================================

ALTER TABLE project_role_assignment RENAME COLUMN is_primary_pd TO is_primary;

COMMENT ON COLUMN project_role_assignment.is_primary IS '该角色在该项目阶段的唯一主审批人';

CREATE UNIQUE INDEX uk_primary_per_role
    ON project_role_assignment (project_code, project_stage, project_role_code)
    WHERE is_primary = true AND deleted = 0;

COMMENT ON INDEX uk_primary_per_role IS '每(项目,阶段,角色)至多一个主审批人；未标主(is_primary=false)不进索引';
