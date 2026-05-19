-- ============================================================
-- V2: 为配置表和项目表增加乐观锁version字段
-- 对应CEO评审决定 D2 + D8
-- ============================================================

ALTER TABLE project ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE project_role_assignment ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE position_assessment_config ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE position_assessor_role_config ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE project_kpi_config ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE func_kpi_config ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE system_param ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
