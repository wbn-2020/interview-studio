-- 与 V66 及记忆来源 DTO 配套在服务器显式发布；本机不执行，不改写历史快照。
-- MySQL DDL 会隐式提交，部署前备份并逐句核验；失败须按服务器恢复流程核查后处理。
ALTER TABLE memory_source_history
    DROP CHECK ck_memory_source_history_kind,
    ADD CONSTRAINT ck_memory_source_history_kind CHECK (
        (history_kind='SOURCE_REVIEW' AND impact_reason IS NULL)
        OR (history_kind='IMPACT' AND impact_reason IS NOT NULL AND impact_reason IN (
            'BASELINE_REVIEW_REQUIRED','SOURCE_BODY_CHANGED','SOURCE_UPSTREAM_CHANGED',
            'SOURCE_ARCHIVED','SOURCE_RESTORED','CARD_BASIS_CHANGED','CARD_ARCHIVED','CARD_RESTORED')));
