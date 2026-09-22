-- ============================================================
-- V30: PD 逐 KPI 校准——单项覆盖分 + 项目任务小计落库 + 单项审计 — PostgreSQL
-- -----------------------------------------------------------------
-- 背景：校准矩阵每行此前直接取 assessment_result 的周期级 composite 当作「项目任务小计」，
--   多项目员工各项目行共享同一 composite（如 2.65）。本迁移补齐三个落点：
--   1) assessment_score 增加 calibrated_score 覆盖分——PD 改单项写覆盖分，不覆盖评估人原始分 score；
--   2) 新增 assessment_project_subtotal 落库项目任务小计（原始 + 校准后），键 (period, assessee, code, stage)；
--   3) 新增 score_kpi_adjustment 记录单项改分审计（old/new）。
--   周期级 composite 计算逻辑不变（assessment_result 仍存周期 composite，供结果发布等使用）。
-- ============================================================

-- 1) 单项校准覆盖分：NULL=未校准，展示用 COALESCE(calibrated_score, score)
ALTER TABLE assessment_score ADD COLUMN calibrated_score DECIMAL(3,1) NULL;
COMMENT ON COLUMN assessment_score.calibrated_score IS 'PD 校准单项覆盖分（NULL=未校准，有效分取 COALESCE(calibrated_score, score)，不覆盖评估人原始分）';

-- 2) 项目任务小计落库：原始小计 + 校准后小计（读路径仍从 assessment_score 实时重算，此处作持久化/审计）
CREATE TABLE assessment_project_subtotal (
    id                BIGSERIAL     PRIMARY KEY,
    period_id         VARCHAR(32)   NOT NULL,
    assessee_id       VARCHAR(32)   NOT NULL,
    project_code      VARCHAR(64)   NOT NULL,
    project_stage     VARCHAR(16)   NOT NULL,
    original_subtotal DECIMAL(5,4)  NOT NULL,
    adjusted_subtotal DECIMAL(5,4)  NOT NULL,
    deleted           SMALLINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uk_project_subtotal UNIQUE (period_id, assessee_id, project_code, project_stage, deleted)
);

COMMENT ON TABLE assessment_project_subtotal IS '项目任务小计落库（PD 逐 KPI 校准后重算的项目小计，键 period+assessee+code+stage）';
COMMENT ON COLUMN assessment_project_subtotal.original_subtotal IS '原始项目任务小计（Σ score×归一化weight）';
COMMENT ON COLUMN assessment_project_subtotal.adjusted_subtotal IS '校准后项目任务小计（Σ COALESCE(calibrated_score,score)×归一化weight）';
COMMENT ON COLUMN assessment_project_subtotal.deleted IS '逻辑删除 0=未删除 1=已删除';
COMMENT ON COLUMN assessment_project_subtotal.version IS '乐观锁版本号';

CREATE INDEX idx_project_subtotal_period ON assessment_project_subtotal(period_id);

-- 3) 单项 KPI 改分审计（追加式，不可变；无逻辑删除与乐观锁）
CREATE TABLE score_kpi_adjustment (
    id            BIGSERIAL     PRIMARY KEY,
    period_id     VARCHAR(32)   NOT NULL,
    task_id       BIGINT        NOT NULL,
    kpi_config_id BIGINT        NOT NULL,
    adjusted_by   VARCHAR(32)   NOT NULL,
    old_score     DECIMAL(3,1)  NOT NULL,
    new_score     DECIMAL(3,1)  NOT NULL,
    reason        VARCHAR(512)  NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE score_kpi_adjustment IS '单项 KPI 改分审计（追加式，改键 task_id+kpi_config_id）';
COMMENT ON COLUMN score_kpi_adjustment.task_id IS '被改分的考核任务 id';
COMMENT ON COLUMN score_kpi_adjustment.kpi_config_id IS '被改分的 KPI 配置 id';
COMMENT ON COLUMN score_kpi_adjustment.adjusted_by IS '改分人工号（employee_id）';
COMMENT ON COLUMN score_kpi_adjustment.old_score IS '改分前有效分（COALESCE(calibrated_score, score)）';
COMMENT ON COLUMN score_kpi_adjustment.new_score IS '改分后有效分';
COMMENT ON COLUMN score_kpi_adjustment.reason IS '改分原因';

CREATE INDEX idx_kpi_adjustment_task ON score_kpi_adjustment(task_id);
