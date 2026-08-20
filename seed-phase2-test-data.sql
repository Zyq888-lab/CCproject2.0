-- ============================================================
-- Phase 2 测试数据重建脚本（清理旧数据 + 创建规范数据）
-- 目标库：jifeng_assessment (localhost:5432, postgres/postgres)
-- 依据：phase2-实操清单.md 第一节「准备数据（配置中心，一次性）」
-- 说明：MyBatis-Plus 逻辑删除 deleted=1；脚本幂等，可重复执行。
-- ============================================================

BEGIN;

-- ---------- 一、清理旧测试数据（物理删除，测试数据重置） ----------
-- 说明：采用物理删除而非逻辑删除，原因有二——
--   (1) 库中已存在历史部分逻辑删除的脏数据（deleted=0/1 混存），
--       再次逻辑删除会撞唯一约束（如 uk_user_role、uk_project_role_employee）；
--   (2) 旧唯一约束 uk_project_role_employee 未含 project_stage（V17 迁移尚未应用），
--       跨阶段重复分配（001/AAA/E002 在 P1、P2）逻辑删除时键值冲突。
-- 该批均为测试数据，物理删除语义正确；按外键依赖从子表到父表执行。
DELETE FROM assessment_score;                    -- 子表：引用 assessment_task
DELETE FROM assessment_task;                     -- 子表：引用 employee/assessment_period
DELETE FROM user_role WHERE user_id <> 'U001';  -- 子表：引用 sys_user（保留 admin 的 ADMIN 角色）
DELETE FROM sys_user WHERE username <> 'admin';  -- 子表：引用 employee（保留 admin）
DELETE FROM position_assessor_role_config;       -- 子表：引用 position_assessment_config/project_role
DELETE FROM project_kpi_config;                  -- 子表：引用 project_role
DELETE FROM project_role_assignment;             -- 子表：引用 project/employee/project_role
DELETE FROM employee_project_participation;      -- 子表：引用 assessment_period/employee/project
DELETE FROM func_kpi_config;                     -- 无外键
DELETE FROM project_role;                        -- 父表
DELETE FROM project;                             -- 父表
DELETE FROM position_assessment_config;          -- 父表
DELETE FROM assessment_period;                   -- 父表

-- ---------- 二、更新员工（E001/E002） ----------
UPDATE employee SET position = '整椅研发工程师', org_name = '研发中心', direct_leader_id = 'E002'
  WHERE employee_id = 'E001';
UPDATE employee SET position = '研发经理', org_name = '研发中心', direct_leader_id = NULL
  WHERE employee_id = 'E002';

-- ---------- 三、创建规范数据 ----------

-- 3.1 项目角色 PDL（项目开发负责人）
INSERT INTO project_role (role_code, role_name, description, is_active)
SELECT 'PDL', '项目开发负责人', '项目开发负责人', TRUE
WHERE NOT EXISTS (SELECT 1 FROM project_role WHERE role_code = 'PDL' AND deleted = 0);

-- 3.2 项目 P001/P2（ACTIVE + 阶段已确认）
INSERT INTO project (project_code, project_name, project_stage, status, stage_confirmed)
SELECT 'P001', '座椅项目', 'P2', 'ACTIVE', TRUE
WHERE NOT EXISTS (SELECT 1 FROM project WHERE project_code = 'P001' AND project_stage = 'P2' AND deleted = 0);

-- 3.3 项目角色分配 P001/P2/PDL/E002
INSERT INTO project_role_assignment (project_code, project_stage, project_role_code, employee_id, is_primary_pd)
SELECT 'P001', 'P2', 'PDL', 'E002', FALSE
WHERE NOT EXISTS (
  SELECT 1 FROM project_role_assignment
  WHERE project_code = 'P001' AND project_stage = 'P2'
    AND project_role_code = 'PDL' AND employee_id = 'E002' AND deleted = 0
);

-- 3.4 岗位配置 研发类/整椅研发工程师（项目占比 70% + 职能占比 30%）
INSERT INTO position_assessment_config (category, position, is_project_based, func_assess_mode, project_weight, func_weight, default_project_role)
SELECT '研发类', '整椅研发工程师', TRUE, 'DIRECT_LEADER', 0.7000, 0.3000, 'PDL'
WHERE NOT EXISTS (
  SELECT 1 FROM position_assessment_config
  WHERE category = '研发类' AND position = '整椅研发工程师' AND deleted = 0
);

-- 3.5 岗位评估角色 → PDL
INSERT INTO position_assessor_role_config (position_config_id, role_code)
SELECT c.id, 'PDL'
FROM position_assessment_config c
WHERE c.category = '研发类' AND c.position = '整椅研发工程师' AND c.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM position_assessor_role_config r
    WHERE r.position_config_id = c.id AND r.role_code = 'PDL' AND r.deleted = 0
  );

-- 3.6 项目 KPI PDL/P2/技术方案质量（权重 1.0）
INSERT INTO project_kpi_config (project_role_code, project_stage, kpi_name, weight, is_active)
SELECT 'PDL', 'P2', '技术方案质量', 1.0000, TRUE
WHERE NOT EXISTS (
  SELECT 1 FROM project_kpi_config
  WHERE project_role_code = 'PDL' AND project_stage = 'P2' AND kpi_name = '技术方案质量' AND deleted = 0
);

-- 3.7 职能 KPI 研发类/整椅研发工程师/工作质量（权重 1.0）
INSERT INTO func_kpi_config (category, position, kpi_name, weight, is_active)
SELECT '研发类', '整椅研发工程师', '工作质量', 1.0000, TRUE
WHERE NOT EXISTS (
  SELECT 1 FROM func_kpi_config
  WHERE category = '研发类' AND position = '整椅研发工程师' AND kpi_name = '工作质量' AND deleted = 0
);

-- 3.8 考核周期 2026H2考核（状态 INIT）
INSERT INTO assessment_period (period_id, period_name, start_date, end_date, status)
SELECT 'a6f1c2d3e4b5a6f1c2d3e4b5a6f1c2d3', '2026H2考核', '2026-08-01', '2026-12-31', 'INIT'
WHERE NOT EXISTS (SELECT 1 FROM assessment_period WHERE period_id = 'a6f1c2d3e4b5a6f1c2d3e4b5a6f1c2d3');

-- 3.9 系统用户 e001/e002（密码 123456，BCrypt strength 12）
INSERT INTO sys_user (user_id, username, password_hash, employee_id, enabled)
SELECT 'U004', 'e001', '$2a$12$MPoNWlCGQnD9otkzkkr6eOXYiPGYZuoScvo/rkmaEOuRX3/Ii.Acu', 'E001', TRUE
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE user_id = 'U004');

INSERT INTO sys_user (user_id, username, password_hash, employee_id, enabled)
SELECT 'U005', 'e002', '$2a$12$MPoNWlCGQnD9otkzkkr6eOXYiPGYZuoScvo/rkmaEOuRX3/Ii.Acu', 'E002', TRUE
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE user_id = 'U005');

-- 3.10 用户角色：e001=员工，e002=评估人
INSERT INTO user_role (user_id, role_type)
SELECT 'U004', '员工'
WHERE NOT EXISTS (SELECT 1 FROM user_role WHERE user_id = 'U004' AND role_type = '员工' AND deleted = 0);

INSERT INTO user_role (user_id, role_type)
SELECT 'U005', '评估人'
WHERE NOT EXISTS (SELECT 1 FROM user_role WHERE user_id = 'U005' AND role_type = '评估人' AND deleted = 0);

-- 3.11 管理员角色（兜底补齐，防止清理误删后 admin 失去 ADMIN 角色）
INSERT INTO user_role (user_id, role_type)
SELECT 'U001', 'ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM user_role WHERE user_id = 'U001' AND role_type = 'ADMIN' AND deleted = 0);

COMMIT;
