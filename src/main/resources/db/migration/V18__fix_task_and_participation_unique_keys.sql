-- ============================================================
-- V18: 修复唯一约束遗漏 project_stage
-- 问题：V10 uk_part_emp_period_project 与 V11 uk_task_unique 均未包含 project_stage，
--       导致同一项目多阶段的参与记录/考核任务被唯一约束静默丢弃（与 V17 修复的
--       project_role_assignment 同类缺陷）。
-- 说明：采用「新增迁移」而非回改 V10/V11，避免已应用库出现 Flyway 校验和冲突。
-- ============================================================

ALTER TABLE employee_project_participation DROP CONSTRAINT IF EXISTS uk_part_emp_period_project;
ALTER TABLE employee_project_participation
    ADD CONSTRAINT uk_part_emp_period_project UNIQUE (employee_id, period_id, project_code, project_stage, deleted);

ALTER TABLE assessment_task DROP CONSTRAINT IF EXISTS uk_task_unique;
ALTER TABLE assessment_task
    ADD CONSTRAINT uk_task_unique UNIQUE NULLS NOT DISTINCT (period_id, assessor_id, assessee_id, project_code, project_stage, task_type, deleted);
