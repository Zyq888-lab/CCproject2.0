-- ============================================================
-- V23: 移除退回死代码相关列 — assessment_task.return_count / max_returns
-- -----------------------------------------------------------------
-- 背景：Phase 2.2 T10 移除退回(RETURN/RETURNED/WITHDRAW/RESUBMIT)死代码，
--   return_count/max_returns 仅服务于「退回上限」逻辑，已无任何引用，
--   故删除两列。
-- ============================================================

ALTER TABLE assessment_task
    DROP COLUMN IF EXISTS return_count,
    DROP COLUMN IF EXISTS max_returns;
