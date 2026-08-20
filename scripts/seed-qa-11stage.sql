-- ============================================================================
-- seed-qa-11stage.sql — Phase 2.0 十一阶段端到端 QA 种子数据（仅本地开发库）
-- ----------------------------------------------------------------------------
-- 前置：后端已重启（Flyway 已应用 V18）
-- 执行 : psql -h 127.0.0.1 -U postgres -d jifeng_assessment -f scripts/seed-qa-11stage.sql
-- 口径 :
--   E003 熊工 = AI技术类/AI产品岗 (AIP 项目角色，被考核人，直属上级 E004)
--   E004 祝工 = AI技术类/AI产品岗 (PM/评估人，AIM 评估角色，创建项目+审批+评分)
--   保留既有配置(员工/用户/角色/project_role/岗位配置/项目KPI)，仅：
--     1) 清空动态测试数据(评分/任务/通知/差异/参与/周期)
--     2) 清理垃圾项目 123/12345/123456/P003 及其角色分配
--     3) 补齐 func_kpi_config AI技术类/AI产品岗（职能任务生成前提）
--     4) 建一个 INIT 周期 P2026Q4（阶段一~十一 的考核载体）
-- 仅本地使用，不要提交到远程仓库
-- ============================================================================

BEGIN;

-- 1. 动态测试数据（按外键依赖倒序）
DELETE FROM assessment_score;                  -- 引用 assessment_task
DELETE FROM assessment_task;                   -- 引用 employee/assessment_period
DELETE FROM notification;                      -- 无外键
DELETE FROM discrepancy_log;                   -- 无外键
DELETE FROM employee_project_participation;    -- 引用 assessment_period/employee/project
DELETE FROM assessment_period;                 -- 父表（此时 task/participation 已删）

-- 2. 测试项目角色分配（引用 project/employee/project_role）；含本次 QA 项目 P004，保证重跑幂等
DELETE FROM project_role_assignment WHERE project_code IN ('123','12345','123456','P003','P004');

-- 3. 测试项目（此时 assignment 已删，无引用）
DELETE FROM project WHERE project_code IN ('123','12345','123456','P003','P004');

-- 4. 补齐 AI技术类/AI产品岗 职能 KPI（FUNCTIONAL 任务生成 + 我的指标展示前提）
INSERT INTO func_kpi_config (category, position, kpi_name, evaluation_criteria, weight, sort_order, is_active)
SELECT 'AI技术类', 'AI产品岗', '工作质量', '职能工作质量与交付效率', 1.00, 1, true
WHERE NOT EXISTS (
  SELECT 1 FROM func_kpi_config
  WHERE category = 'AI技术类' AND position = 'AI产品岗' AND deleted = 0
);

-- 5. 建 INIT 考核周期（阶段四 launch 的载体）
INSERT INTO assessment_period (period_id, period_name, start_date, end_date, status)
VALUES ('P2026Q4', '2026年Q4考核', '2026-10-01', '2026-12-31', 'INIT');

COMMIT;

-- ============================================================================
-- 自检（可选）
-- ============================================================================
-- SELECT 'task' t, count(*) FROM assessment_task
-- UNION ALL SELECT 'score', count(*) FROM assessment_score
-- UNION ALL SELECT 'participation', count(*) FROM employee_project_participation
-- UNION ALL SELECT 'period', count(*) FROM assessment_period
-- UNION ALL SELECT 'func_kpi_ai', count(*) FROM func_kpi_config WHERE category='AI技术类';
