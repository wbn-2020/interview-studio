-- 由服务器显式迁移；这里只保存个人证据关联与建议显示意图，不复制原答或模型正文。
CREATE TABLE growth_evidence_use (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requirement_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_object_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    evidence_revision BIGINT NULL,
    evidence_part_id VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NULL,
    evidence_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    coverage VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    comparability_json MEDIUMTEXT NOT NULL,
    reason TEXT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_growth_use_owner (owner_id,id),
    UNIQUE KEY uk_growth_use_identity (owner_id,target_version_id,requirement_id,evidence_key),
    KEY ix_growth_use_directory (owner_id,target_version_id,created_at DESC,id),
    KEY ix_growth_use_status (owner_id,target_version_id,status,created_at DESC,id),
    KEY ix_growth_use_evidence (owner_id,evidence_key,status,target_version_id),
    CONSTRAINT fk_growth_use_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_growth_use_target FOREIGN KEY (owner_id,target_id,target_version_id)
        REFERENCES preparation_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_growth_use_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_growth_use_evidence CHECK (
      (evidence_kind IN ('TRAINING_ANSWER','REPLAY_JUDGMENT','MEMORY_RATING')
        AND evidence_version_id IS NULL AND evidence_revision IS NULL AND evidence_part_id IS NULL)
      OR (evidence_kind='MANUAL_FEEDBACK' AND evidence_version_id IS NULL
        AND evidence_revision IS NOT NULL AND evidence_revision BETWEEN 1 AND 100 AND evidence_part_id IS NULL)
      OR (evidence_kind='AI_OBSERVATION' AND evidence_version_id IS NULL AND evidence_revision IS NULL
        AND evidence_part_id IS NOT NULL AND evidence_part_id IN ('f1','f2','f3','f4','f5','f6','f7','f8','f9','f10','f11','f12'))
      OR (evidence_kind='RECOLLECTION' AND evidence_version_id IS NOT NULL
        AND evidence_revision IS NULL AND evidence_part_id IS NULL)),
    CONSTRAINT ck_growth_use_key CHECK (evidence_key=CONCAT(evidence_kind,':',evidence_object_id,':',
      COALESCE(evidence_version_id,'-'),':',COALESCE(CAST(evidence_revision AS CHAR),'-'),':',COALESCE(evidence_part_id,'-'))),
    CONSTRAINT ck_growth_use_coverage CHECK (coverage IN ('SUPPORTS','PARTIAL','CONTEXT_ONLY','INSUFFICIENT')),
    CONSTRAINT ck_growth_use_status CHECK (status IN ('ACTIVE','WITHDRAWN')),
    CONSTRAINT ck_growth_use_comparison CHECK (JSON_VALID(comparability_json)
      AND JSON_TYPE(CAST(comparability_json AS JSON))='OBJECT' AND JSON_LENGTH(comparability_json)=5
      AND JSON_CONTAINS_PATH(comparability_json,'all','$.dimension','$.scenario','$.technology','$.assistance','$.date')
      AND OCTET_LENGTH(comparability_json)<=16384),
    CONSTRAINT ck_growth_use_reason CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 2000),
    CONSTRAINT ck_growth_use_clock CHECK (updated_at>=created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 每次有效修改追加快照；当前适用性读取原领域，不能改写当时的个人判断。
CREATE TABLE growth_evidence_use_history (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    use_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    change_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    source_stamp_json TEXT NOT NULL,
    target_stamp CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preview_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,use_id,revision),
    CONSTRAINT fk_growth_use_history FOREIGN KEY (owner_id,use_id)
        REFERENCES growth_evidence_use (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_growth_use_history_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_growth_use_history_change CHECK (
      (revision=0 AND change_kind='CREATED') OR (revision>0 AND change_kind IN ('UPDATED','WITHDRAWN','RESTORED'))),
    CONSTRAINT ck_growth_use_history_snapshot CHECK (JSON_VALID(snapshot_json)
      AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json)<=32768),
    CONSTRAINT ck_growth_use_history_stamp CHECK (JSON_VALID(source_stamp_json)
      AND JSON_TYPE(CAST(source_stamp_json AS JSON))='OBJECT' AND OCTET_LENGTH(source_stamp_json)<=4096)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE growth_next_step_preference (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    requirement_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    revision BIGINT NOT NULL,
    visibility VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_growth_preference_owner (owner_id,id),
    UNIQUE KEY uk_growth_preference_subject (owner_id,subject_kind,subject_key),
    KEY ix_growth_preference_directory (owner_id,visibility,created_at DESC,id),
    CONSTRAINT fk_growth_preference_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_growth_preference_target FOREIGN KEY (owner_id,target_id,target_version_id)
        REFERENCES preparation_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_growth_preference_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_growth_preference_subject CHECK (
      (subject_kind='SOURCE' AND source_kind IS NOT NULL AND source_id IS NOT NULL
        AND source_kind IN ('REVIEW_ACTION','MEMORY_CARD','PREPARATION_TASK')
        AND target_id IS NULL AND target_version_id IS NULL AND requirement_id IS NULL
        AND subject_key=CONCAT(source_kind,':',source_id))
      OR (subject_kind='TARGET_REQUIREMENT' AND source_kind IS NULL AND source_id IS NULL
        AND target_id IS NOT NULL AND target_version_id IS NOT NULL AND requirement_id IS NOT NULL
        AND subject_key=CONCAT(target_id,':',target_version_id,':',requirement_id))),
    CONSTRAINT ck_growth_preference_visibility CHECK (visibility IN ('HIDDEN','VISIBLE')),
    CONSTRAINT ck_growth_preference_clock CHECK (updated_at>=created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE growth_next_step_preference_history (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preference_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    change_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,preference_id,revision),
    CONSTRAINT fk_growth_preference_history FOREIGN KEY (owner_id,preference_id)
        REFERENCES growth_next_step_preference (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_growth_preference_history_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_growth_preference_history_change CHECK (
      (revision=0 AND change_kind='CREATED') OR (revision>0 AND change_kind='UPDATED')),
    CONSTRAINT ck_growth_preference_history_snapshot CHECK (JSON_VALID(snapshot_json)
      AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json)<=32768)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
