-- ============================================================
-- V29: 项目级校准提交表 calibration_submission — PostgreSQL
-- -----------------------------------------------------------------
-- 背景：PD 校准提交粒度从「周期级」（assessment_period.calibration_submitted_at 单字段）
--   细化为「项目级」：每个 PD 独立提交自己主 PD 负责的项目，互不影响；
--   assessment_period.calibration_submitted_at 改为派生语义——本周期所有涉及
--   (project_code, project_stage) 都已提交后才置非空（供总裁确认页/监控页/待处理计数）。
--   提交单元 = (period_id, project_code, project_stage) 唯一，重复提交更新 submitted_at（幂等）。
-- ============================================================

CREATE TABLE calibration_submission (
    id                       BIGSERIAL   PRIMARY KEY,
    period_id                VARCHAR(64) NOT NULL,
    project_code             VARCHAR(64) NOT NULL,
    project_stage            VARCHAR(16) NOT NULL,
    submitted_by_employee_id VARCHAR(64),
    submitted_at             TIMESTAMP,
    deleted                  INT         NOT NULL DEFAULT 0,
    created_at               TIMESTAMP,
    updated_at               TIMESTAMP,
    version                  BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_calibration_submission UNIQUE (period_id, project_code, project_stage)
);

COMMENT ON TABLE calibration_submission IS '项目级校准提交（PD 逐项目提交，UNIQUE(period_id, project_code, project_stage)）';
COMMENT ON COLUMN calibration_submission.period_id IS '考核周期 id';
COMMENT ON COLUMN calibration_submission.project_code IS '项目编码';
COMMENT ON COLUMN calibration_submission.project_stage IS '项目阶段';
COMMENT ON COLUMN calibration_submission.submitted_by_employee_id IS '提交人工号（employee_id）';
COMMENT ON COLUMN calibration_submission.submitted_at IS '提交时间（NULL=未提交，总裁退回后置空）';
COMMENT ON COLUMN calibration_submission.deleted IS '逻辑删除 0=未删除 1=已删除';
COMMENT ON COLUMN calibration_submission.version IS '乐观锁版本号';

CREATE INDEX idx_calibration_submission_period ON calibration_submission(period_id);
