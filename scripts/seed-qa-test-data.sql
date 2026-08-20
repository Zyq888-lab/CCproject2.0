-- ============================================================================
-- seed-qa-test-data.sql — Phase 2.0 全场景 QA 测试数据（仅本地开发库）
-- ----------------------------------------------------------------------------
-- 前置：先执行 clean-test-data.sql 清空，再执行本脚本
-- 执行方式 : psql -h 127.0.0.1 -U postgres -d jifeng_assessment -f scripts/seed-qa-test-data.sql
-- 数据口径 :
--   员工 E001 张工   (研发技术类/整椅研发岗, ACTIVE, 直属上级 E002) —— 被考核人
--   员工 E002 李总   (管理类/项目经理, ACTIVE, 直属上级 null)       —— PM + 评估人
--   项目 P001 座榜项目 阶段 P2 ACTIVE stage_confirmed=true
--   项目角色 PDL(项目开发负责人) / PM(项目经理)
--   角色分配 张工=P001/P2/PDL, 李总=P001/P2/PM(主PD)
--   岗位配置 研发技术类/整椅研发岗 项目70%/职能30% default PDL, 评估人角色=PM
--   项目KPI PDL/P2/任务完成率(1.0) 职能KPI 研发技术类/整椅研发岗/工作质量(1.0)
--   周期 P2026Q3 2026年Q3考核 2026-07-01~2026-09-30 INIT
-- 说明 : 用户账号(sys_user) 由 API POST /api/v1/users 创建（bcrypt 加密），不在本脚本
-- 仅本地使用，不要提交到远程仓库
-- ============================================================================

BEGIN;

-- 1. 员工（E001 张工 / E002 李总）
INSERT INTO employee (employee_id, name, email, category, position, org_name, direct_leader_id, status)
VALUES
  ('E001', '张工', 'zhanggong@jifeng.com', '研发技术类', '整椅研发岗', '研发部', 'E002', 'ACTIVE'),
  ('E002', '李总', 'lizong@jifeng.com',     '管理类',   '项目经理', '项目管理部', NULL,  'ACTIVE');

-- 2. 项目（P001 座榜项目 / P002 被拒重提场景 / 阶段 P2 / ACTIVE / 已确认阶段）
INSERT INTO project (project_code, project_name, project_stage, description, status, stage_confirmed)
VALUES
  ('P001', '座榜项目',   'P2', 'Phase 2.0 QA 测试项目', 'ACTIVE', true),
  ('P002', '测试项目二', 'P2', '被拒后重新提交场景',     'ACTIVE', true);

-- 3. 项目角色（PDL 项目开发负责人 / PM 项目经理）
INSERT INTO project_role (role_code, role_name, description, is_active)
VALUES
  ('PDL', '项目开发负责人', '项目开发负责人（默认项目角色）', true),
  ('PM',  '项目经理',       '项目经理（评估人角色）',       true);

-- 4. 项目角色分配（张工=P001/P2/PDL；李总=P001/P2/PM 主PD）
INSERT INTO project_role_assignment (project_code, project_stage, project_role_code, employee_id, is_primary_pd)
VALUES
  ('P001', 'P2', 'PDL', 'E001', false),
  ('P001', 'P2', 'PM',  'E002', true);

-- 5. 岗位考核配置（研发技术类/整椅研发岗 项目70%/职能30% 默认项目角色 PDL）
INSERT INTO position_assessment_config (category, position, is_project_based, default_project_role, func_assess_mode, project_weight, func_weight)
VALUES ('研发技术类', '整椅研发岗', true, 'PDL', 'DIRECT_LEADER', 0.70, 0.30);

-- 6. 岗位评估人角色（评估人角色 = PM，指向 5 中新建配置）
INSERT INTO position_assessor_role_config (position_config_id, role_code)
SELECT id, 'PM' FROM position_assessment_config WHERE category = '研发技术类' AND position = '整椅研发岗';

-- 7. 项目 KPI（PDL / P2 / 任务完成率 / 权重 1.0）
INSERT INTO project_kpi_config (project_role_code, project_stage, kpi_name, evaluation_criteria, weight, sort_order, is_active)
VALUES ('PDL', 'P2', '任务完成率', '按时按质完成所分配的项目任务', 1.00, 1, true);

-- 8. 职能 KPI（研发技术类/整椅研发岗 / 工作质量 / 权重 1.0）
INSERT INTO func_kpi_config (category, position, kpi_name, evaluation_criteria, weight, sort_order, is_active)
VALUES ('研发技术类', '整椅研发岗', '工作质量', '职能工作质量与交付效率', 1.00, 1, true);

-- 9. 考核周期（2026年Q3考核 INIT）
INSERT INTO assessment_period (period_id, period_name, start_date, end_date, status)
VALUES ('P2026Q3', '2026年Q3考核', '2026-07-01', '2026-09-30', 'INIT');

COMMIT;

-- ============================================================================
-- 种子结果自检（执行后取消注释查看）
-- ============================================================================
-- SELECT 'employee' t, count(*) FROM employee WHERE employee_id IN ('E001','E002');
-- SELECT 'project' t, count(*) FROM project WHERE project_code='P001';
-- SELECT 'project_role' t, count(*) FROM project_role WHERE role_code IN ('PDL','PM');
-- SELECT 'role_assignment' t, count(*) FROM project_role_assignment WHERE project_code='P001';
-- SELECT 'pos_config' t, count(*) FROM position_assessment_config WHERE category='研发技术类';
-- SELECT 'assessor_role' t, count(*) FROM position_assessor_role_config;
-- SELECT 'project_kpi' t, count(*) FROM project_kpi_config;
-- SELECT 'func_kpi' t, count(*) FROM func_kpi_config;
-- SELECT 'period' t, count(*) FROM assessment_period WHERE period_id='P2026Q3';
