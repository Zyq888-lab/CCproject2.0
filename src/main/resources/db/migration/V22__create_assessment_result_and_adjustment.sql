-- ============================================================
-- V22: 结果落库表 — assessment_result + score_adjustment — PostgreSQL
-- -----------------------------------------------------------------
-- 背景：Phase 2.1 校准确认闭环——打分完成后按员工聚合 composite 总分落
--   assessment_result（员工×周期一行，@Version 乐观锁），PD 改分写
--   adjusted_score 并记 score_adjustment 审计（改键 assessment_result_id，
--   不是 task_id/score_id）。改分对象是 composite 总分，不落在
--   assessment_score（那是 task × kpi 指标分行）。
-- ============================================================

-- 员工×周期结果行：原始分 + 调整分
CREATE TABLE assessment_result (
    id             BIGSERIAL     PRIMARY KEY,
    period_id      VARCHAR(32)   NOT NULL,
    assessee_id    VARCHAR(32)   NOT NULL,
    original_score DECIMAL(5,4)  NOT NULL,
    adjusted_score DECIMAL(5,4)  NOT NULL,
    deleted        SMALLINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT fk_result_period FOREIGN KEY (period_id) REFERENCES assessment_period(period_id),
    CONSTRAINT fk_result_assessee FOREIGN KEY (assessee_id) REFERENCES employee(employee_id),
    CONSTRAINT uk_result_assessee_period UNIQUE (assessee_id, period_id, deleted)
);

COMMENT ON TABLE assessment_result IS '员工×周期考核结果（composite 总分）';
COMMENT ON COLUMN assessment_result.original_score IS '原始 composite 总分（聚合生成时计算）';
COMMENT ON COLUMN assessment_result.adjusted_score IS '调整后总分（PD 改分后写，初始等于 original_score）';
COMMENT ON COLUMN assessment_result.deleted IS '逻辑删除 0=未删除 1=已删除';
COMMENT ON COLUMN assessment_result.version IS '乐观锁版本号';

CREATE INDEX idx_result_period ON assessment_result(period_id);

-- 改分审计表（追加式，不可变；无逻辑删除与乐观锁）
CREATE TABLE score_adjustment (
    id                   BIGSERIAL     PRIMARY KEY,
    assessment_result_id BIGINT        NOT NULL,
    adjusted_by          VARCHAR(32)   NOT NULL,
    old_score            DECIMAL(5,4)  NOT NULL,
    new_score            DECIMAL(5,4)  NOT NULL,
    reason               VARCHAR(512)  NULL,
    created_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_adjustment_result FOREIGN KEY (assessment_result_id) REFERENCES assessment_result(id)
);

COMMENT ON TABLE score_adjustment IS '改分审计表（追加式）';
COMMENT ON COLUMN score_adjustment.assessment_result_id IS '被改分的 assessment_result 行';
COMMENT ON COLUMN score_adjustment.adjusted_by IS '改分人工号（employee_id）';
COMMENT ON COLUMN score_adjustment.old_score IS '改分前 composite 总分';
COMMENT ON COLUMN score_adjustment.new_score IS '改分后 composite 总分';
COMMENT ON COLUMN score_adjustment.reason IS '改分原因';

CREATE INDEX idx_adjustment_result ON score_adjustment(assessment_result_id);
