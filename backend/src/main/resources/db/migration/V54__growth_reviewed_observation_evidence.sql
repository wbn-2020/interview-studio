-- 受审观察以记录 ID 和修订（首版为 0）固定身份，版本 ID 与片段编号必须为空。
ALTER TABLE growth_evidence_use
    DROP CHECK ck_growth_use_evidence,
    ADD CONSTRAINT ck_growth_use_evidence_v54 CHECK (
      (evidence_kind IN ('TRAINING_ANSWER','REPLAY_JUDGMENT','MEMORY_RATING')
        AND evidence_version_id IS NULL AND evidence_revision IS NULL AND evidence_part_id IS NULL)
      OR (evidence_kind='MANUAL_FEEDBACK' AND evidence_version_id IS NULL
        AND evidence_revision IS NOT NULL AND evidence_revision BETWEEN 1 AND 100 AND evidence_part_id IS NULL)
      OR (evidence_kind='AI_OBSERVATION' AND evidence_version_id IS NULL AND evidence_revision IS NULL
        AND evidence_part_id IS NOT NULL AND evidence_part_id IN ('f1','f2','f3','f4','f5','f6','f7','f8','f9','f10','f11','f12'))
      OR (evidence_kind='RECOLLECTION' AND evidence_version_id IS NOT NULL
        AND evidence_revision IS NULL AND evidence_part_id IS NULL)
      OR (evidence_kind='REVIEWED_OBSERVATION' AND evidence_version_id IS NULL
        AND evidence_revision IS NOT NULL AND evidence_revision BETWEEN 0 AND 9007199254740991
        AND evidence_part_id IS NULL));
