-- ============================================================
-- V12: 考核评分表 — PostgreSQL
-- 对应 Phase 2.0 A14/A15 项目/职能考核打分
-- 字段定义来源: eng-plan 第十一章 + phase2-技术增量设计.md §一
-- ============================================================

CREATE TABLE assessment_score (
    id            BIGSERIAL     PRIMARY KEY,
    task_id       BIGINT        NOT NULL,
    kpi_config_id BIGINT        NOT NULL,
    kpi_type      VARCHAR(16)   NOT NULL,
    score         DECIMAL(3,1)  NOT NULL,
    evidence_url  VARCHAR(512)  NULL,
    status        VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',
    deleted       SMALLINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT fk_score_task FOREIGN KEY (task_id) REFERENCES assessment_task(id),
    CONSTRAINT uk_score_kpi UNIQUE (task_id, kpi_config_id, kpi_type, deleted)
);

COMMENT ON TABLE assessment_score IS '考核评分表';
COMMENT ON COLUMN assessment_score.kpi_config_id IS 'KPI配置ID，多态引用 project_kpi_config 或 func_kpi_config';
COMMENT ON COLUMN assessment_score.kpi_type IS 'PROJECT/FUNCTIONAL';
COMMENT ON COLUMN assessment_score.score IS '得分 1.0-5.0，1位小数';
COMMENT ON COLUMN assessment_score.evidence_url IS '凭证文件路径';
COMMENT ON COLUMN assessment_score.status IS 'DRAFT/SUBMITTED';
COMMENT ON COLUMN assessment_score.deleted IS '逻辑删除 0=未删除 1=已删除';
COMMENT ON COLUMN assessment_score.version IS '乐观锁版本号';

CREATE INDEX idx_score_task ON assessment_score(task_id);
