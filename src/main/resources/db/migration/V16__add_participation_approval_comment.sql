-- ============================================================
-- V16: 项目参与表新增审批意见字段 — PostgreSQL
-- 对应 Phase 2.0 卡点2：PM 审批弹窗的「审批意见」输入框
-- ============================================================

ALTER TABLE employee_project_participation
    ADD COLUMN IF NOT EXISTS approval_comment VARCHAR(500) NULL;

COMMENT ON COLUMN employee_project_participation.approval_comment IS '审批意见';
