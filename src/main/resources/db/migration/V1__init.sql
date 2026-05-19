-- ============================================================
-- V1: 阶段1初始建表（13张表）— PostgreSQL
-- 项目制绩效考核系统 — 配置中心
-- ============================================================

-- 1. 员工表
CREATE TABLE employee (
    employee_id     VARCHAR(32)   NOT NULL PRIMARY KEY,
    name            VARCHAR(64)   NOT NULL,
    email           VARCHAR(128)  NOT NULL,
    category        VARCHAR(64)   NOT NULL,
    position        VARCHAR(128)  NOT NULL,
    org_name        VARCHAR(128)  NOT NULL,
    direct_leader_id VARCHAR(32)  NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    deleted         SMALLINT      NOT NULL DEFAULT 0,  -- 逻辑删除 0=未删除 1=已删除
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE employee IS '员工表';
COMMENT ON COLUMN employee.deleted IS '逻辑删除 0=未删除 1=已删除';

-- 2. 系统用户表
CREATE TABLE sys_user (
    user_id         VARCHAR(32)   NOT NULL PRIMARY KEY,
    username        VARCHAR(64)   NOT NULL,
    password_hash   VARCHAR(128)  NOT NULL,
    employee_id     VARCHAR(32)   NULL,
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    deleted         SMALLINT      NOT NULL DEFAULT 0,  -- 逻辑删除 0=未删除 1=已删除
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_user_username UNIQUE (username),
    CONSTRAINT fk_user_employee FOREIGN KEY (employee_id) REFERENCES employee(employee_id)
);
COMMENT ON TABLE sys_user IS '系统用户表';
COMMENT ON COLUMN sys_user.deleted IS '逻辑删除 0=未删除 1=已删除';

-- 3. 用户角色关联表
CREATE TABLE user_role (
    id              BIGSERIAL     PRIMARY KEY,
    user_id         VARCHAR(32)   NOT NULL,
    role_type       VARCHAR(32)   NOT NULL,
    deleted         SMALLINT      NOT NULL DEFAULT 0,  -- 逻辑删除 0=未删除 1=已删除
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user(user_id),
    CONSTRAINT uk_user_role UNIQUE (user_id, role_type, deleted)
);
COMMENT ON TABLE user_role IS '用户角色关联表';
COMMENT ON COLUMN user_role.deleted IS '逻辑删除 0=未删除 1=已删除';

-- 4. 项目角色表（动态可配置）
CREATE TABLE project_role (
    role_code       VARCHAR(32)   NOT NULL PRIMARY KEY,
    role_name       VARCHAR(64)   NOT NULL,
    description     TEXT          NULL,
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    deleted         SMALLINT      NOT NULL DEFAULT 0,  -- 逻辑删除 0=未删除 1=已删除
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE project_role IS '项目角色表（动态可配置）';
COMMENT ON COLUMN project_role.deleted IS '逻辑删除 0=未删除 1=已删除';

-- 5. 项目表
CREATE TABLE project (
    project_code    VARCHAR(32)   NOT NULL PRIMARY KEY,
    project_name    VARCHAR(128)  NOT NULL,
    project_stage   VARCHAR(8)    NOT NULL,
    description     TEXT          NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    stage_confirmed BOOLEAN       NOT NULL DEFAULT FALSE,
    confirmed_by    VARCHAR(32)   NULL,
    confirmed_at    TIMESTAMP     NULL,
    deleted         SMALLINT      NOT NULL DEFAULT 0,  -- 逻辑删除 0=未删除 1=已删除
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE project IS '项目表';
COMMENT ON COLUMN project.stage_confirmed IS 'PM是否已确认阶段';
COMMENT ON COLUMN project.deleted IS '逻辑删除 0=未删除 1=已删除';

-- 6. 项目角色分配表
CREATE TABLE project_role_assignment (
    id               BIGSERIAL     PRIMARY KEY,
    project_code     VARCHAR(32)   NOT NULL,
    project_role_code VARCHAR(32)  NOT NULL,
    employee_id      VARCHAR(32)   NOT NULL,
    is_primary_pd    BOOLEAN       NOT NULL DEFAULT FALSE,
    deleted          SMALLINT      NOT NULL DEFAULT 0,  -- 逻辑删除 0=未删除 1=已删除
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pra_project FOREIGN KEY (project_code) REFERENCES project(project_code),
    CONSTRAINT fk_pra_role FOREIGN KEY (project_role_code) REFERENCES project_role(role_code),
    CONSTRAINT fk_pra_employee FOREIGN KEY (employee_id) REFERENCES employee(employee_id),
    CONSTRAINT uk_project_role_employee UNIQUE (project_code, project_role_code, employee_id, deleted)
);
COMMENT ON TABLE project_role_assignment IS '项目角色分配表';
COMMENT ON COLUMN project_role_assignment.is_primary_pd IS '是否PD负责人';
COMMENT ON COLUMN project_role_assignment.deleted IS '逻辑删除 0=未删除 1=已删除';

-- 7. 岗位考核配置表
CREATE TABLE position_assessment_config (
    id                BIGSERIAL     PRIMARY KEY,
    category          VARCHAR(64)   NOT NULL,
    position          VARCHAR(128)  NOT NULL,
    is_project_based  BOOLEAN       NOT NULL DEFAULT TRUE,
    default_project_role VARCHAR(32) NULL,
    func_assess_mode  VARCHAR(32)   NOT NULL DEFAULT 'DIRECT_LEADER',
    project_weight    DECIMAL(5,4)  NOT NULL DEFAULT 0.7000,
    func_weight       DECIMAL(5,4)  NOT NULL DEFAULT 0.3000,
    deleted           SMALLINT      NOT NULL DEFAULT 0,  -- 逻辑删除 0=未删除 1=已删除
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_category_position UNIQUE (category, position, deleted)
);
COMMENT ON TABLE position_assessment_config IS '岗位考核配置表';
COMMENT ON COLUMN position_assessment_config.is_project_based IS '是否纳入项目制';
COMMENT ON COLUMN position_assessment_config.func_assess_mode IS 'DIRECT_LEADER/ORG_LEADER';
COMMENT ON COLUMN position_assessment_config.deleted IS '逻辑删除 0=未删除 1=已删除';

-- 8. 岗位考核人角色关联表（规范化，D10）
CREATE TABLE position_assessor_role_config (
    id                BIGSERIAL     PRIMARY KEY,
    position_config_id BIGINT       NOT NULL,
    role_code         VARCHAR(32)   NOT NULL,
    deleted           SMALLINT      NOT NULL DEFAULT 0,  -- 逻辑删除 0=未删除 1=已删除
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_parc_config FOREIGN KEY (position_config_id) REFERENCES position_assessment_config(id),
    CONSTRAINT fk_parc_role FOREIGN KEY (role_code) REFERENCES project_role(role_code),
    CONSTRAINT uk_config_role UNIQUE (position_config_id, role_code, deleted)
);
COMMENT ON TABLE position_assessor_role_config IS '岗位考核人角色关联表（规范化，D10）';
COMMENT ON COLUMN position_assessor_role_config.deleted IS '逻辑删除 0=未删除 1=已删除';

-- 9. 项目KPI指标配置表
CREATE TABLE project_kpi_config (
    id                  BIGSERIAL     PRIMARY KEY,
    project_role_code   VARCHAR(32)   NOT NULL,
    project_stage       VARCHAR(8)    NOT NULL,
    kpi_name            VARCHAR(128)  NOT NULL,
    evaluation_criteria TEXT          NULL,
    weight              DECIMAL(5,4)  NOT NULL,
    sort_order          INT           NOT NULL DEFAULT 0,
    is_active           BOOLEAN       NOT NULL DEFAULT TRUE,
    deleted             SMALLINT      NOT NULL DEFAULT 0,  -- 逻辑删除 0=未删除 1=已删除
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pkc_role FOREIGN KEY (project_role_code) REFERENCES project_role(role_code)
);
COMMENT ON TABLE project_kpi_config IS '项目KPI指标配置表';
COMMENT ON COLUMN project_kpi_config.evaluation_criteria IS '1-5分评价标准';
COMMENT ON COLUMN project_kpi_config.deleted IS '逻辑删除 0=未删除 1=已删除';

-- 10. 职能KPI指标配置表
CREATE TABLE func_kpi_config (
    id                  BIGSERIAL     PRIMARY KEY,
    category            VARCHAR(64)   NOT NULL,
    position            VARCHAR(128)  NOT NULL,
    kpi_name            VARCHAR(128)  NOT NULL,
    evaluation_criteria TEXT          NULL,
    weight              DECIMAL(5,4)  NOT NULL,
    sort_order          INT           NOT NULL DEFAULT 0,
    is_active           BOOLEAN       NOT NULL DEFAULT TRUE,
    deleted             SMALLINT      NOT NULL DEFAULT 0,  -- 逻辑删除 0=未删除 1=已删除
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE func_kpi_config IS '职能KPI指标配置表';
COMMENT ON COLUMN func_kpi_config.deleted IS '逻辑删除 0=未删除 1=已删除';

-- 11. 考核周期表
CREATE TABLE assessment_period (
    period_id    VARCHAR(32)   NOT NULL PRIMARY KEY,
    period_name  VARCHAR(128)  NOT NULL,
    start_date   DATE          NOT NULL,
    end_date     DATE          NOT NULL,
    status       VARCHAR(16)   NOT NULL DEFAULT 'INIT',
    deleted      SMALLINT      NOT NULL DEFAULT 0,  -- 逻辑删除 0=未删除 1=已删除
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE assessment_period IS '考核周期表';
COMMENT ON COLUMN assessment_period.status IS 'INIT/ONGOING/CALIBRATING/COMPLETED';
COMMENT ON COLUMN assessment_period.deleted IS '逻辑删除 0=未删除 1=已删除';

-- 12. 系统参数表
CREATE TABLE system_param (
    id           BIGSERIAL     PRIMARY KEY,
    param_key    VARCHAR(64)   NOT NULL,
    param_value  VARCHAR(512)  NOT NULL,
    description  VARCHAR(256)  NULL,
    deleted      SMALLINT      NOT NULL DEFAULT 0,  -- 逻辑删除 0=未删除 1=已删除
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_system_param_key UNIQUE (param_key)
);
COMMENT ON TABLE system_param IS '系统参数表';
COMMENT ON COLUMN system_param.deleted IS '逻辑删除 0=未删除 1=已删除';

-- 13. 配置向导进度表
CREATE TABLE wizard_progress (
    id               BIGSERIAL     PRIMARY KEY,
    user_id          VARCHAR(32)   NOT NULL,
    current_step     INT           NOT NULL DEFAULT 1,
    completed_steps  VARCHAR(128)  NULL DEFAULT '',
    deleted          SMALLINT      NOT NULL DEFAULT 0,  -- 逻辑删除 0=未删除 1=已删除
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wizard_user UNIQUE (user_id, deleted)
);
COMMENT ON TABLE wizard_progress IS '配置向导进度表';
COMMENT ON COLUMN wizard_progress.current_step IS '当前所在步骤(1-7)';
COMMENT ON COLUMN wizard_progress.completed_steps IS '已完成的步骤，逗号分隔如1,2,3';
COMMENT ON COLUMN wizard_progress.deleted IS '逻辑删除 0=未删除 1=已删除';
