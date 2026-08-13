-- ============================================================
-- V11: 考核任务表 — PostgreSQL
-- 对应 Phase 2.0 考核关系自动生成 + 任务状态机
-- 字段定义来源: eng-plan 第十一章 + phase2-技术增量设计.md §一
-- ============================================================

CREATE TABLE assessment_task (
    id            BIGSERIAL     PRIMARY KEY,
    period_id     VARCHAR(32)   NOT NULL,
    assessor_id   VARCHAR(32)   NOT NULL,
    assessee_id   VARCHAR(32)   NOT NULL,
    project_code  VARCHAR(32)   NULL,
    project_stage VARCHAR(8)    NULL,
    task_type     VARCHAR(16)   NOT NULL,
    status        VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    return_count  INT           NOT NULL DEFAULT 0,
    max_returns   INT           NOT NULL DEFAULT 3,
    deleted       SMALLINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT fk_task_period FOREIGN KEY (period_id) REFERENCES assessment_period(period_id),
    CONSTRAINT fk_task_assessor FOREIGN KEY (assessor_id) REFERENCES employee(employee_id),
    CONSTRAINT fk_task_assessee FOREIGN KEY (assessee_id) REFERENCES employee(employee_id),
    CONSTRAINT uk_task_unique UNIQUE NULLS NOT DISTINCT (period_id, assessor_id, assessee_id, project_code, task_type, deleted)
);

COMMENT ON TABLE assessment_task IS '考核任务表（考核人×被考核人配对）';
COMMENT ON COLUMN assessment_task.assessor_id IS '考核人 employee_id';
COMMENT ON COLUMN assessment_task.assessee_id IS '被考核人 employee_id';
COMMENT ON COLUMN assessment_task.project_code IS '项目考核时填写，职能考核为 NULL';
COMMENT ON COLUMN assessment_task.project_stage IS '项目阶段，配合 project 联合主键，职能考核为 NULL';
COMMENT ON COLUMN assessment_task.task_type IS 'PROJECT/FUNCTIONAL';
COMMENT ON COLUMN assessment_task.status IS 'PENDING/IN_PROGRESS/SUBMITTED/RETURNED/CONFIRMED/CANCELED';
COMMENT ON COLUMN assessment_task.return_count IS '退回次数';
COMMENT ON COLUMN assessment_task.max_returns IS '最大退回次数(从系统参数读取)';
COMMENT ON COLUMN assessment_task.deleted IS '逻辑删除 0=未删除 1=已删除';
COMMENT ON COLUMN assessment_task.version IS '乐观锁版本号';

CREATE INDEX idx_task_period ON assessment_task(period_id);
CREATE INDEX idx_task_assessor ON assessment_task(assessor_id);
CREATE INDEX idx_task_assessee ON assessment_task(assessee_id);
