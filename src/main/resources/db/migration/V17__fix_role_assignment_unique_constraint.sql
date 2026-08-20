-- ============================================================
-- V17: 修复 project_role_assignment 唯一约束未包含 project_stage
-- 背景：V9 把 project 改为联合主键 (project_code, project_stage)，
--       并给 project_role_assignment 加了 project_stage 列，
--       但漏改了唯一约束 uk_project_role_employee，
--       它仍按 (project_code, project_role_code, employee_id, deleted) 去重。
-- 现象：同一员工分配到同一项目的不同阶段（同一角色）时，
--       应用层 RoleAssignmentService 的去重检查已含 project_stage、能正常放行，
--       但插入时被数据库唯一约束拦截，报「已被分配」错误。
-- 修复：删除旧唯一约束，重建为包含 project_stage 的唯一约束。
-- ============================================================

ALTER TABLE project_role_assignment DROP CONSTRAINT IF EXISTS uk_project_role_employee;

ALTER TABLE project_role_assignment
    ADD CONSTRAINT uk_project_role_stage_employee
    UNIQUE (project_code, project_stage, project_role_code, employee_id, deleted);

COMMENT ON CONSTRAINT uk_project_role_stage_employee ON project_role_assignment
    IS '同项目同阶段同角色同员工唯一；允许同一员工跨阶段重复分配';
