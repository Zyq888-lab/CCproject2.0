-- ============================================================
-- V3: 种子数据 — 默认系统参数 + 初始ADMIN员工记录 — PostgreSQL
-- 注: ADMIN用户账号由 DataInitializer 组件在首次启动时创建（确保bcrypt密码哈希正确）
-- ============================================================

-- 默认系统参数
INSERT INTO system_param (param_key, param_value, description) VALUES
('NEED_PRESIDENT_CONFIRM', 'false', '是否需要总裁确认环节（阶段2启用）'),
('MAX_RETURN_TIMES', '3', 'PD退回次数上限'),
('REQUIRE_EVIDENCE', 'false', '评分是否必须上传凭证'),
('DEFAULT_PROJECT_WEIGHT', '0.70', '默认项目考核权重'),
('DEFAULT_FUNC_WEIGHT', '0.30', '默认职能考核权重');

-- ADMIN员工基础记录（user账号由DataInitializer创建）
INSERT INTO employee (employee_id, name, email, category, position, org_name, status)
SELECT 'ADMIN', '系统管理员', 'admin@jifeng.com', '管理类', '系统管理员', '信息部', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM employee WHERE employee_id = 'ADMIN');
