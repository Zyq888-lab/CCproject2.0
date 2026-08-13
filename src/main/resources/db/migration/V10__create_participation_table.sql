-- ============================================================
-- V10: 员工项目参与表 — PostgreSQL
-- 对应 Phase 2.0 A13 项目参与录入与审批
-- 字段定义来源: eng-plan 第十一章 + phase2-技术增量设计.md §一
-- ============================================================

CREATE TABLE employee_project_participation (
    id                BIGSERIAL     PRIMARY KEY,
    employee_id       VARCHAR(32)   NOT NULL,
    project_code      VARCHAR(32)   NOT NULL,
    project_stage     VARCHAR(8)    NOT NULL,
    participation_rate DECIMAL(5,2) NOT NULL,
    suggested_rate    DECIMAL(5,2)  NULL,
    status            VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    approved_by       VARCHAR(32)   NULL,
    approved_at       TIMESTAMP     NULL,
    period_id         VARCHAR(32)   NOT NULL,
    deleted           SMALLINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT fk_part_employee FOREIGN KEY (employee_id) REFERENCES employee(employee_id),
    CONSTRAINT fk_part_project FOREIGN KEY (project_code, project_stage) REFERENCES project(project_code, project_stage),
    CONSTRAINT fk_part_period FOREIGN KEY (period_id) REFERENCES assessment_period(period_id),
    CONSTRAINT uk_part_emp_period_project UNIQUE (employee_id, period_id, project_code, deleted)
);

COMMENT ON TABLE employee_project_participation IS '员工项目参与表';
COMMENT ON COLUMN employee_project_participation.participation_rate IS '投入比重 1-100(%)';
COMMENT ON COLUMN employee_project_participation.suggested_rate IS '审批人建议投入比重';
COMMENT ON COLUMN employee_project_participation.status IS 'PENDING/APPROVED/REJECTED/CANCELLED';
COMMENT ON COLUMN employee_project_participation.deleted IS '逻辑删除 0=未删除 1=已删除';
COMMENT ON COLUMN employee_project_participation.version IS '乐观锁版本号';

CREATE INDEX idx_part_period ON employee_project_participation(period_id);
CREATE INDEX idx_part_employee ON employee_project_participation(employee_id);
