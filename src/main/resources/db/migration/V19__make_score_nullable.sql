-- ============================================================
-- V19: assessment_score.score 允许为空 — 草稿行在正式评分前无得分
-- -----------------------------------------------------------------
-- 背景：ensureScore（凭证上传前置）会插入空 DRAFT 行（score 为 NULL），
--   saveDraft 也允许「只填部分指标」（item.score 可为 NULL）。
--   但 V12 建表时 score 加了 NOT NULL，导致空草稿插入时：
--     ERROR: null value in column "score" violates not-null constraint → HTTP 500
-- 修复：score 改为可空，评分提交的 1.0-5.0 范围校验由 ScoreService.submit 在应用层兜底。
-- ============================================================

ALTER TABLE assessment_score ALTER COLUMN score DROP NOT NULL;
