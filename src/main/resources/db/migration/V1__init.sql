-- ============================================================
-- V1: 阶段1初始建表（13张表）
-- 项目制绩效考核系统 — 配置中心
-- ============================================================

-- 1. 员工表
CREATE TABLE employee (
    employee_id     VARCHAR(32)   NOT NULL PRIMARY KEY COMMENT '工号',
    name            VARCHAR(64)   NOT NULL COMMENT '姓名',
    email           VARCHAR(128)  NOT NULL COMMENT '邮箱',
    category        VARCHAR(64)   NOT NULL COMMENT '岗位分类（一类）',
    position        VARCHAR(128)  NOT NULL COMMENT '岗位名称（二类）',
    org_name        VARCHAR(128)  NOT NULL COMMENT '职能组织/部门',
    direct_leader_id VARCHAR(32)  NULL COMMENT '直属上级工号',
    status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删除 1=已删除',
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. 系统用户表
CREATE TABLE sys_user (
    user_id         VARCHAR(32)   NOT NULL PRIMARY KEY COMMENT '用户ID',
    username        VARCHAR(64)   NOT NULL UNIQUE COMMENT '登录名',
    password_hash   VARCHAR(128)  NOT NULL COMMENT 'bcrypt密码哈希',
    employee_id     VARCHAR(32)   NULL COMMENT '关联员工工号',
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删除 1=已删除',
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_employee FOREIGN KEY (employee_id) REFERENCES employee(employee_id)
);

-- 3. 用户角色关联表
CREATE TABLE user_role (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         VARCHAR(32)   NOT NULL COMMENT 'FK→sys_user',
    role_type       VARCHAR(32)   NOT NULL COMMENT 'ADMIN/PRESIDENT/PD/PM/ASSESSOR/EMPLOYEE',
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删除 1=已删除',
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user(user_id),
    UNIQUE KEY uk_user_role (user_id, role_type, deleted)
);

-- 4. 项目角色表（动态可配置）
CREATE TABLE project_role (
    role_code       VARCHAR(32)   NOT NULL PRIMARY KEY COMMENT '角色代码，如PDL',
    role_name       VARCHAR(64)   NOT NULL COMMENT '角色名称，如项目研发负责人',
    description     TEXT          NULL COMMENT '角色说明',
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删除 1=已删除',
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 5. 项目表
CREATE TABLE project (
    project_code    VARCHAR(32)   NOT NULL PRIMARY KEY COMMENT '项目编码，如PRJ2025001',
    project_name    VARCHAR(128)  NOT NULL COMMENT '项目中文名称',
    project_stage   VARCHAR(8)    NOT NULL COMMENT 'P2/P3/P4/P5',
    description     TEXT          NULL COMMENT '项目说明',
    status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    stage_confirmed BOOLEAN       NOT NULL DEFAULT FALSE COMMENT 'PM是否已确认阶段',
    confirmed_by    VARCHAR(32)   NULL COMMENT '确认人',
    confirmed_at    TIMESTAMP     NULL COMMENT '确认时间',
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删除 1=已删除',
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 6. 项目角色分配表
CREATE TABLE project_role_assignment (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_code     VARCHAR(32)  NOT NULL COMMENT 'FK→project',
    project_role_code VARCHAR(32) NOT NULL COMMENT 'FK→project_role',
    employee_id      VARCHAR(32)  NOT NULL COMMENT 'FK→employee',
    is_primary_pd    BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '是否PD负责人',
    deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删除 1=已删除',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pra_project FOREIGN KEY (project_code) REFERENCES project(project_code),
    CONSTRAINT fk_pra_role FOREIGN KEY (project_role_code) REFERENCES project_role(role_code),
    CONSTRAINT fk_pra_employee FOREIGN KEY (employee_id) REFERENCES employee(employee_id),
    UNIQUE KEY uk_project_role_employee (project_code, project_role_code, employee_id, deleted)
);

-- 7. 岗位考核配置表
CREATE TABLE position_assessment_config (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    category          VARCHAR(64)  NOT NULL COMMENT '岗位分类',
    position          VARCHAR(128) NOT NULL COMMENT '岗位名称',
    is_project_based  BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '是否纳入项目制',
    default_project_role VARCHAR(32) NULL COMMENT '默认项目角色',
    func_assess_mode  VARCHAR(32)  NOT NULL DEFAULT 'DIRECT_LEADER' COMMENT 'DIRECT_LEADER/ORG_LEADER',
    project_weight    DECIMAL(5,4) NOT NULL DEFAULT 0.7000 COMMENT '项目考核权重',
    func_weight       DECIMAL(5,4) NOT NULL DEFAULT 0.3000 COMMENT '职能考核权重',
    deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删除 1=已删除',
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_category_position (category, position, deleted)
);

-- 8. 岗位考核人角色关联表（规范化，D10）
CREATE TABLE position_assessor_role_config (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    position_config_id BIGINT      NOT NULL COMMENT 'FK→position_assessment_config',
    role_code         VARCHAR(32)  NOT NULL COMMENT 'FK→project_role，考核人角色代码',
    deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删除 1=已删除',
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_parc_config FOREIGN KEY (position_config_id) REFERENCES position_assessment_config(id),
    CONSTRAINT fk_parc_role FOREIGN KEY (role_code) REFERENCES project_role(role_code),
    UNIQUE KEY uk_config_role (position_config_id, role_code, deleted)
);

-- 9. 项目KPI指标配置表
CREATE TABLE project_kpi_config (
    id                  BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_role_code   VARCHAR(32) NOT NULL COMMENT 'FK→project_role',
    project_stage       VARCHAR(8)  NOT NULL COMMENT 'P2/P3/P4/P5',
    kpi_name            VARCHAR(128) NOT NULL COMMENT '指标名称',
    evaluation_criteria TEXT        NULL COMMENT '1-5分评价标准',
    weight              DECIMAL(5,4) NOT NULL COMMENT '权重',
    sort_order          INT         NOT NULL DEFAULT 0 COMMENT '排序',
    is_active           BOOLEAN     NOT NULL DEFAULT TRUE,
    deleted             TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删除 1=已删除',
    created_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pkc_role FOREIGN KEY (project_role_code) REFERENCES project_role(role_code)
);

-- 10. 职能KPI指标配置表
CREATE TABLE func_kpi_config (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    category            VARCHAR(64)  NOT NULL COMMENT '岗位分类',
    position            VARCHAR(128) NOT NULL COMMENT '岗位名称',
    kpi_name            VARCHAR(128) NOT NULL COMMENT '指标名称',
    evaluation_criteria TEXT         NULL COMMENT '评价标准',
    weight              DECIMAL(5,4) NOT NULL COMMENT '权重',
    sort_order          INT          NOT NULL DEFAULT 0 COMMENT '排序',
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删除 1=已删除',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 11. 考核周期表
CREATE TABLE assessment_period (
    period_id    VARCHAR(32)   NOT NULL PRIMARY KEY COMMENT '周期ID',
    period_name  VARCHAR(128)  NOT NULL COMMENT '周期名称，如2026年上半年考核',
    start_date   DATE          NOT NULL COMMENT '开始日期',
    end_date     DATE          NOT NULL COMMENT '结束日期',
    status       VARCHAR(16)   NOT NULL DEFAULT 'INIT' COMMENT 'INIT/ONGOING/CALIBRATING/COMPLETED',
    deleted      TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删除 1=已删除',
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 12. 系统参数表
CREATE TABLE system_param (
    id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    param_key    VARCHAR(64)   NOT NULL UNIQUE COMMENT '参数键',
    param_value  VARCHAR(512)  NOT NULL COMMENT '参数值',
    description  VARCHAR(256)  NULL COMMENT '参数说明',
    deleted      TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删除 1=已删除',
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 13. 配置向导进度表
CREATE TABLE wizard_progress (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id          VARCHAR(32)  NOT NULL COMMENT '用户ID',
    current_step     INT          NOT NULL DEFAULT 1 COMMENT '当前所在步骤(1-7)',
    completed_steps  VARCHAR(128) NULL DEFAULT '' COMMENT '已完成的步骤，逗号分隔如1,2,3',
    deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删除 1=已删除',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_wizard_user (user_id, deleted)
);
