-- ============================================================
-- V13: 通知表 — PostgreSQL
-- 对应 Phase 2.0 站内消息通知（内联触发，无独立通知中心）
-- 字段定义来源: eng-plan 第十一章 + phase2-技术增量设计.md §一
-- ============================================================

CREATE TABLE notification (
    id           BIGSERIAL     PRIMARY KEY,
    recipient_id VARCHAR(32)   NOT NULL,
    title        VARCHAR(128)  NOT NULL,
    content      TEXT          NULL,
    type         VARCHAR(32)   NOT NULL,
    is_read      BOOLEAN       NOT NULL DEFAULT FALSE,
    target_url   VARCHAR(256)  NULL,
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE notification IS '通知消息表';
COMMENT ON COLUMN notification.recipient_id IS '接收人 user_id（无外键，低耦合）';
COMMENT ON COLUMN notification.type IS 'TASK_ASSIGNED/RETURNED/CONFIRMED/URGE';
COMMENT ON COLUMN notification.is_read IS '是否已读';
COMMENT ON COLUMN notification.target_url IS '点击跳转路径';

CREATE INDEX idx_notification_recipient ON notification(recipient_id, is_read);
