-- ============================================================
-- V27: 项目确认表 + 总裁项目角色/退回参数 — PostgreSQL
-- Track B：总裁逐项目确认（PENDING/APPROVED/RETURNED），确认单元 = (period_id, project_code)
--   与「一个项目跨阶段一个确认项」对齐。return_count 配合 PRESIDENT_RETURN_TIMES 做退回上限。
-- ============================================================

-- 总裁项目角色（roleCode 固定为 PRESIDENT，解析器硬编码它）
INSERT INTO project_role (role_code, role_name, description, is_active)
VALUES ('PRESIDENT', '总裁', '项目负责总裁（最后确认人）', true)
ON CONFLICT (role_code) DO NOTHING;

-- 总裁退回次数上限（安全阀；与 MAX_RETURN_TIMES 语义不同，后者是 PD→评估人任务退回）
INSERT INTO system_param (param_key, param_value, description)
VALUES ('PRESIDENT_RETURN_TIMES', '3', '总裁单项目退回次数上限')
ON CONFLICT (param_key) DO NOTHING;

CREATE TABLE project_confirmation (
    id                       BIGSERIAL PRIMARY KEY,
    period_id                VARCHAR(64) NOT NULL,
    project_code             VARCHAR(64) NOT NULL,
    status                   VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/APPROVED/RETURNED
    return_count             INT         NOT NULL DEFAULT 0,
    return_reason            VARCHAR(500),
    confirmed_by_employee_id VARCHAR(64),
    confirmed_at             TIMESTAMP,
    deleted                  INT         NOT NULL DEFAULT 0,
    created_at               TIMESTAMP,
    updated_at               TIMESTAMP,
    version                  BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_project_confirmation UNIQUE (period_id, project_code)
);
