-- ============================================================
-- V14: 差异报告表 — PostgreSQL
-- 对应 Phase 2.0 考核关系生成的异常清单（缺岗位配置/考核人缺失/上级为空）
-- 字段定义来源: eng-plan 第十一章 + eng-review 问题4（差异持久化）
-- ============================================================

CREATE TABLE discrepancy_log (
    id           BIGSERIAL     PRIMARY KEY,
    period_id    VARCHAR(32)   NOT NULL,
    employee_id  VARCHAR(32)   NOT NULL,
    project_code VARCHAR(32)   NULL,
    type         VARCHAR(32)   NOT NULL,
    detail       TEXT          NULL,
    resolved     BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE discrepancy_log IS '考核关系生成差异报告表';
COMMENT ON COLUMN discrepancy_log.employee_id IS '异常员工工号';
COMMENT ON COLUMN discrepancy_log.project_code IS '关联项目，缺岗位配置场景为 NULL';
COMMENT ON COLUMN discrepancy_log.type IS 'NO_POSITION_CONFIG/NO_ASSESSOR/NO_LEADER';
COMMENT ON COLUMN discrepancy_log.detail IS '异常详情描述';
COMMENT ON COLUMN discrepancy_log.resolved IS '是否已处理（ADMIN 补配置后标记）';

CREATE INDEX idx_discrepancy_period ON discrepancy_log(period_id);
