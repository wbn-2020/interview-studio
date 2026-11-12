-- 服务器发布时显式执行；新增管理状态，不建立尝试或评分表，不消费知识搜索 outbox。
CREATE TABLE memory_review_gate (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    review_required BOOLEAN NOT NULL,
    last_review_decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_history_sequence BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,card_id),
    CONSTRAINT fk_memory_gate_card FOREIGN KEY (owner_id,card_id) REFERENCES memory_card (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_memory_gate_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_memory_gate_required CHECK (review_required IN (0,1)),
    CONSTRAINT ck_memory_gate_sequence CHECK (last_history_sequence BETWEEN 0 AND 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE memory_source_history (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_number BIGINT NOT NULL,
    gate_revision BIGINT NOT NULL,
    history_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    impact_reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_source_history_sequence (owner_id,card_id,sequence_number),
    UNIQUE KEY uk_memory_source_history_identity (owner_id,card_id,id),
    KEY ix_memory_source_pending_body (owner_id,card_id,impact_reason,gate_revision),
    CONSTRAINT fk_memory_source_history_version FOREIGN KEY (owner_id,card_id,card_version_id) REFERENCES memory_card_version (owner_id,card_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_memory_source_history_gate FOREIGN KEY (owner_id,card_id) REFERENCES memory_review_gate (owner_id,card_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_memory_source_history_sequence CHECK (sequence_number BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_memory_source_history_revision CHECK (gate_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_memory_source_history_kind CHECK ((history_kind='SOURCE_REVIEW' AND impact_reason IS NULL) OR
      (history_kind='IMPACT' AND impact_reason IS NOT NULL AND impact_reason IN ('BASELINE_REVIEW_REQUIRED','SOURCE_BODY_CHANGED','SOURCE_ARCHIVED','SOURCE_RESTORED','CARD_BASIS_CHANGED','CARD_ARCHIVED','CARD_RESTORED'))),
    CONSTRAINT ck_memory_source_history_json CHECK (JSON_VALID(snapshot_json) AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json) <= 16384)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE memory_schedule (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    cycle_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    pause_reason VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    algorithm_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parameter_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    phase VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    step INT NULL,
    stability DOUBLE NULL,
    difficulty DOUBLE NULL,
    suggested_due_at DATETIME(6) NOT NULL,
    last_review_at DATETIME(6) NULL,
    defer_until DATETIME(6) NULL,
    last_rated_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_history_sequence BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,card_id),
    KEY ix_memory_schedule_due (owner_id,status,suggested_due_at,card_id),
    CONSTRAINT fk_memory_schedule_gate FOREIGN KEY (owner_id,card_id) REFERENCES memory_review_gate (owner_id,card_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_memory_schedule_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_memory_schedule_sequence CHECK (last_history_sequence BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_memory_schedule_status CHECK ((status='ACTIVE' AND pause_reason IS NULL) OR
      (status='PAUSED' AND pause_reason IS NOT NULL AND pause_reason IN ('MANUAL','SOURCE_REVIEW','CARD_ARCHIVED'))),
    CONSTRAINT ck_memory_schedule_phase CHECK (phase IN ('LEARNING','REVIEW','RELEARNING')),
    CONSTRAINT ck_memory_schedule_step CHECK (step IS NULL OR step >= 0),
    CONSTRAINT ck_memory_schedule_stability CHECK (stability IS NULL OR stability > 0),
    CONSTRAINT ck_memory_schedule_difficulty CHECK (difficulty IS NULL OR difficulty BETWEEN 1 AND 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE memory_schedule_history (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_number BIGINT NOT NULL,
    schedule_revision BIGINT NOT NULL,
    change_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    impact_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_schedule_history_sequence (owner_id,card_id,sequence_number),
    CONSTRAINT fk_memory_schedule_history_card FOREIGN KEY (owner_id,card_id) REFERENCES memory_schedule (owner_id,card_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_memory_schedule_history_version FOREIGN KEY (owner_id,card_id,card_version_id) REFERENCES memory_card_version (owner_id,card_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_memory_schedule_history_impact FOREIGN KEY (owner_id,card_id,impact_id) REFERENCES memory_source_history (owner_id,card_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_memory_schedule_history_sequence CHECK (sequence_number BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_memory_schedule_history_revision CHECK (schedule_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_memory_schedule_history_kind CHECK ((change_kind IN ('ENABLE','DEFER','PAUSE','RESUME','RESET') AND impact_id IS NULL) OR
      (change_kind IN ('SOURCE_PAUSE','CARD_PAUSE') AND impact_id IS NOT NULL)),
    CONSTRAINT ck_memory_schedule_history_json CHECK (JSON_VALID(snapshot_json) AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json) <= 16384)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE memory_review_settings (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    zone_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    daily_new_budget INT NOT NULL,
    daily_review_budget INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id),
    CONSTRAINT fk_memory_settings_owner FOREIGN KEY (owner_id) REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_memory_settings_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_memory_settings_new_budget CHECK (daily_new_budget BETWEEN 0 AND 1000),
    CONSTRAINT ck_memory_settings_review_budget CHECK (daily_review_budget BETWEEN 0 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 旧卡没有完整的来源观察期；建立保守基线，不根据当下正文相同推断历史安全。
-- 基线影响使用卡 UUID 作为本表事件 UUID，保持两次 INSERT 的身份与摘要完全一致。
SET @memory_baseline_at = UTC_TIMESTAMP(6);
INSERT INTO memory_review_gate (owner_id,card_id,revision,review_required,last_review_decision_id,last_history_sequence,updated_at)
SELECT owner_id,id,0,TRUE,NULL,1,@memory_baseline_at FROM memory_card;

INSERT INTO memory_source_history (id,owner_id,card_id,card_version_id,sequence_number,gate_revision,history_kind,impact_reason,snapshot_json,created_at)
SELECT id,owner_id,id,current_version_id,1,0,'IMPACT','BASELINE_REVIEW_REQUIRED',
  JSON_OBJECT('sequence',1,'kind','IMPACT','decision',NULL,'impact',JSON_OBJECT(
    'id',id,'cardId',id,'cardVersionId',current_version_id,'gateRevision',0,'reason','BASELINE_REVIEW_REQUIRED',
    'source',NULL,'reviewRequired',JSON_EXTRACT('true','$'),'actor',JSON_OBJECT('kind','SYSTEM','ownerId',NULL),
    'createdAt',DATE_FORMAT(@memory_baseline_at,'%Y-%m-%dT%H:%i:%s.%fZ'))),@memory_baseline_at
FROM memory_card;
