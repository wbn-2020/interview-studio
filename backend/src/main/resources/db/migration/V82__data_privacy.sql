-- 只存无正文状态／检查点；独立账本由 STUDIO_PRIVACY_LEDGER_DIRECTORY 持久化，
-- 不属于业务数据库或完整业务导出包，恢复时必须以账本最新头为准。
CREATE TABLE privacy_ledger_checkpoint (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ledger_sequence BIGINT NOT NULL,
    ledger_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id),
    CONSTRAINT fk_privacy_checkpoint_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_privacy_checkpoint_sequence CHECK (ledger_sequence BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_privacy_checkpoint_hash CHECK (ledger_hash REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE privacy_object_state (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    latest_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ledger_sequence BIGINT NOT NULL,
    previous_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    trashed_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,object_kind,object_id),
    KEY ix_privacy_owner_state_time (owner_id,state,updated_at,object_kind,object_id),
    CONSTRAINT fk_privacy_state_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_privacy_object_kind CHECK (object_kind IN ('KNOWLEDGE','PREPARATION','QUESTION',
        'MEMORY_CARD','BOARD','MATERIAL','AI_TASK','AI_AUTHORIZATION','TRAINING','REVIEW_FEEDBACK','RECOLLECTION',
        'PREPARATION_TASK','REVIEW_ACTION','QUESTION_COLLECTION','INTERVIEW_EVENT','MATERIAL_EXCERPT','EXPORT')),
    CONSTRAINT ck_privacy_object_state CHECK (state IN ('AVAILABLE','TRASHED','PURGED','REVOKED')),
    CONSTRAINT ck_privacy_object_revision CHECK (revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_privacy_object_ledger CHECK (ledger_sequence BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_privacy_trashed_time CHECK ((state='TRASHED' AND trashed_at IS NOT NULL) OR state<>'TRASHED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
