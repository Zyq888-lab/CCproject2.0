-- ============================================================
-- V26: 用户激活默认初始密码参数 + 强制改密标记列 — PostgreSQL
-- Track A（用户激活 + 强制改密）。PRESIDENT 项目角色 / PRESIDENT_RETURN_TIMES
--   归 Track B，与 V27 一起做（见 design 文档 Eng review 锁定 V26 拆分）。
-- ============================================================

-- 默认初始密码（激活账号用；可被系统参数页改）
INSERT INTO system_param (param_key, param_value, description)
VALUES ('DEFAULT_INITIAL_PASSWORD', '123456', '激活账号的默认初始密码')
ON CONFLICT (param_key) DO NOTHING;

-- 强制改密标记（true=登录后除改密/登出/me外一律403）
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN sys_user.must_change_password IS '首登强制改密标记（true=登录后除改密/登出外一律403）';
