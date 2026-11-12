-- 安排只保存显示偏好和名义分钟；源状态、回忆排程与完成证据保留在原域。
CREATE TABLE today_placement (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    planned_date DATE NULL,
    plan_zone_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    estimated_minutes INT NULL,
    priority INT NOT NULL,
    order_value INT NOT NULL,
    date_locked BOOLEAN NOT NULL,
    visibility VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    suppressed_date DATE NULL,
    suppressed_zone_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_today_placement_identity (owner_id,id),
    UNIQUE KEY uk_today_placement_source (owner_id,source_kind,source_id),
    KEY ix_today_placement_directory (owner_id,updated_at DESC,id),
    KEY ix_today_placement_order (owner_id,source_kind,order_value,source_id),
    KEY ix_today_placement_date (owner_id,source_kind,planned_date,order_value,source_id),
    CONSTRAINT fk_today_placement_owner FOREIGN KEY (owner_id) REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_today_placement_kind CHECK (source_kind IN ('KNOWLEDGE_DRAFT','TRAINING_SESSION','PREPARATION_TASK','MEMORY_CARD','REVIEW_ACTION','INTERVIEW_EVENT')),
    CONSTRAINT ck_today_placement_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_today_placement_date CHECK ((planned_date IS NULL AND plan_zone_id IS NULL) OR
      (planned_date IS NOT NULL AND plan_zone_id IS NOT NULL AND source_kind IN ('KNOWLEDGE_DRAFT','TRAINING_SESSION','REVIEW_ACTION'))),
    CONSTRAINT ck_today_placement_estimate CHECK (estimated_minutes IS NULL OR
      (estimated_minutes BETWEEN 1 AND 1440 AND source_kind IN ('KNOWLEDGE_DRAFT','TRAINING_SESSION','MEMORY_CARD','REVIEW_ACTION'))),
    CONSTRAINT ck_today_placement_priority CHECK (priority BETWEEN 1 AND 5),
    CONSTRAINT ck_today_placement_order CHECK (order_value BETWEEN 0 AND 1000000),
    CONSTRAINT ck_today_placement_lock CHECK (date_locked IN (0,1) AND (date_locked=0 OR source_kind='PREPARATION_TASK' OR
      (planned_date IS NOT NULL AND source_kind IN ('KNOWLEDGE_DRAFT','TRAINING_SESSION','REVIEW_ACTION')))),
    CONSTRAINT ck_today_placement_visibility CHECK (visibility='VISIBLE' OR
      (visibility='PAUSED' AND source_kind IN ('KNOWLEDGE_DRAFT','TRAINING_SESSION','REVIEW_ACTION'))),
    CONSTRAINT ck_today_placement_suppression CHECK ((suppressed_date IS NULL AND suppressed_zone_id IS NULL) OR
      (suppressed_date IS NOT NULL AND suppressed_zone_id IS NOT NULL)),
    CONSTRAINT ck_today_placement_clock CHECK (updated_at >= created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE today_placement_history (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    placement_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,placement_id,revision),
    CONSTRAINT fk_today_placement_history FOREIGN KEY (owner_id,placement_id) REFERENCES today_placement (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_today_placement_history_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_today_placement_history_json CHECK (JSON_VALID(snapshot_json) AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json) <= 32768)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE today_day_budget (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    local_date DATE NOT NULL,
    zone_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    available_minutes INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,local_date,zone_id),
    CONSTRAINT fk_today_budget_owner FOREIGN KEY (owner_id) REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_today_budget_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_today_budget_minutes CHECK (available_minutes BETWEEN 0 AND 1440),
    CONSTRAINT ck_today_budget_clock CHECK (updated_at >= created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE today_day_budget_history (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    local_date DATE NOT NULL,
    zone_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,local_date,zone_id,revision),
    CONSTRAINT fk_today_budget_history FOREIGN KEY (owner_id,local_date,zone_id) REFERENCES today_day_budget (owner_id,local_date,zone_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_today_budget_history_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_today_budget_history_json CHECK (JSON_VALID(snapshot_json) AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json) <= 32768)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE today_plan_generation (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id),
    CONSTRAINT fk_today_generation_owner FOREIGN KEY (owner_id) REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_today_generation_value CHECK (generation BETWEEN 0 AND 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
