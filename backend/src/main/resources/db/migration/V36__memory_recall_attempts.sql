-- 只在服务器发布时执行。既有排程与历史保留，RATE 通过向前迁移扩展允许值。
ALTER TABLE memory_schedule_history
    DROP CHECK ck_memory_schedule_history_kind,
    ADD CONSTRAINT ck_memory_schedule_history_kind CHECK (
      (change_kind IN ('ENABLE','RATE','DEFER','PAUSE','RESUME','RESET') AND impact_id IS NULL) OR
      (change_kind IN ('SOURCE_PAUSE','CARD_PAUSE') AND impact_id IS NOT NULL));

CREATE TABLE memory_cram_batch (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(200) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    zone_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_count INT NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_cram_owner (owner_id,id),
    KEY ix_memory_cram_directory (owner_id,created_at,id),
    CONSTRAINT fk_memory_cram_owner FOREIGN KEY (owner_id) REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_memory_cram_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_memory_cram_dates CHECK (start_date >= '1000-01-01' AND end_date <= '9999-12-31' AND DATEDIFF(end_date,start_date) BETWEEN 0 AND 89),
    CONSTRAINT ck_memory_cram_count CHECK (card_count BETWEEN 1 AND 200),
    CONSTRAINT ck_memory_cram_json CHECK (JSON_VALID(snapshot_json) AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json)<=131072)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE memory_cram_member (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position_index INT NOT NULL,
    card_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_version_number INT NOT NULL,
    card_revision BIGINT NOT NULL,
    PRIMARY KEY (batch_id,position_index),
    UNIQUE KEY uk_memory_cram_member (owner_id,batch_id,card_id),
    CONSTRAINT fk_memory_cram_member_batch FOREIGN KEY (owner_id,batch_id) REFERENCES memory_cram_batch (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_memory_cram_member_version FOREIGN KEY (owner_id,card_id,card_version_id) REFERENCES memory_card_version (owner_id,card_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_memory_cram_position CHECK (position_index BETWEEN 1 AND 200),
    CONSTRAINT ck_memory_cram_version CHECK (card_version_number BETWEEN 1 AND 1000),
    CONSTRAINT ck_memory_cram_revision CHECK (card_revision BETWEEN 0 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE memory_recall_attempt (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_number BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mode VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    count_regular BOOLEAN NOT NULL,
    cram_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    start_snapshot_json MEDIUMTEXT NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    started_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    revealed_at DATETIME(6) NULL,
    rated_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    rating VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL,
    invalidation_reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_event_sequence BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_attempt_identity (owner_id,card_id,id),
    UNIQUE KEY uk_memory_attempt_slot_state (owner_id,card_id,id,state),
    UNIQUE KEY uk_memory_attempt_sequence (owner_id,card_id,sequence_number),
    KEY ix_memory_attempt_open (owner_id,state,started_at,id),
    CONSTRAINT fk_memory_attempt_version FOREIGN KEY (owner_id,card_id,card_version_id) REFERENCES memory_card_version (owner_id,card_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_memory_attempt_cram FOREIGN KEY (owner_id,cram_batch_id,card_id) REFERENCES memory_cram_member (owner_id,batch_id,card_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_memory_attempt_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_memory_attempt_sequence CHECK (sequence_number BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_memory_attempt_event_sequence CHECK (last_event_sequence BETWEEN 1 AND 3),
    CONSTRAINT ck_memory_attempt_mode CHECK (count_regular IN (0,1) AND
      ((mode='REGULAR' AND count_regular=1 AND cram_batch_id IS NULL) OR (mode='CRAM' AND cram_batch_id IS NOT NULL))),
    CONSTRAINT ck_memory_attempt_times CHECK (updated_at>=started_at AND
      (revealed_at IS NULL OR (revealed_at>=started_at AND revealed_at<=updated_at)) AND
      (rated_at IS NULL OR (revealed_at IS NOT NULL AND rated_at>=revealed_at AND rated_at=closed_at)) AND
      (closed_at IS NULL OR closed_at=updated_at)),
    CONSTRAINT ck_memory_attempt_state CHECK (
      (state='THINKING' AND revealed_at IS NULL AND rated_at IS NULL AND closed_at IS NULL AND rating IS NULL AND invalidation_reason IS NULL) OR
      (state='REVEALED' AND revealed_at IS NOT NULL AND rated_at IS NULL AND closed_at IS NULL AND rating IS NULL AND invalidation_reason IS NULL) OR
      (state='RATED' AND revealed_at IS NOT NULL AND rated_at IS NOT NULL AND closed_at IS NOT NULL AND rating IS NOT NULL AND invalidation_reason IS NULL) OR
      (state='SKIPPED' AND rated_at IS NULL AND closed_at IS NOT NULL AND rating IS NULL AND invalidation_reason IS NULL) OR
      (state='INVALIDATED' AND rated_at IS NULL AND closed_at IS NOT NULL AND rating IS NULL AND invalidation_reason IS NOT NULL)),
    CONSTRAINT ck_memory_attempt_rating CHECK (rating IS NULL OR rating IN ('AGAIN','HARD','GOOD','EASY')),
    CONSTRAINT ck_memory_attempt_start_json CHECK (JSON_VALID(start_snapshot_json) AND JSON_TYPE(CAST(start_snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(start_snapshot_json)<=32768),
    CONSTRAINT ck_memory_attempt_json CHECK (JSON_VALID(snapshot_json) AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json)<=131072)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- owner 写锁串行开始与终结；唯一键与复合外键额外保护一卡一占位及所属关系。
CREATE TABLE memory_active_attempt (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (owner_id,card_id),
    UNIQUE KEY uk_memory_active_attempt (attempt_id),
    CONSTRAINT fk_memory_active_attempt FOREIGN KEY (owner_id,card_id,attempt_id,attempt_state) REFERENCES memory_recall_attempt (owner_id,card_id,id,state) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_memory_active_state CHECK (attempt_state IN ('THINKING','REVEALED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE memory_attempt_event (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_sequence BIGINT NOT NULL,
    sequence_number BIGINT NOT NULL,
    attempt_revision BIGINT NOT NULL,
    event_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    count_regular BOOLEAN NOT NULL,
    rate_basis_kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL,
    occurred_at DATETIME(6) NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_attempt_event_sequence (owner_id,attempt_id,sequence_number),
    UNIQUE KEY uk_memory_attempt_event_kind (owner_id,attempt_id,event_kind),
    KEY ix_memory_rating_window (owner_id,event_kind,count_regular,occurred_at,card_id,attempt_sequence),
    KEY ix_memory_card_rating (owner_id,card_id,event_kind,count_regular,occurred_at,attempt_sequence),
    CONSTRAINT fk_memory_event_attempt FOREIGN KEY (owner_id,card_id,attempt_id) REFERENCES memory_recall_attempt (owner_id,card_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_memory_event_sequence CHECK (sequence_number BETWEEN 1 AND 3 AND attempt_sequence BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_memory_event_revision CHECK (attempt_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_memory_event_kind CHECK (event_kind IN ('START','REVEAL','RATE','SKIP','INVALIDATE')),
    CONSTRAINT ck_memory_event_regular CHECK (count_regular IN (0,1)),
    CONSTRAINT ck_memory_event_basis CHECK (
      (event_kind='RATE' AND count_regular=1 AND rate_basis_kind IS NOT NULL AND rate_basis_kind IN ('NEW','REVIEW')) OR
      ((event_kind<>'RATE' OR count_regular=0) AND rate_basis_kind IS NULL)),
    CONSTRAINT ck_memory_event_json CHECK (JSON_VALID(snapshot_json) AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json)<=131072)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
