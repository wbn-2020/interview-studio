-- 服务器发布时显式执行；清单固定实际外发内容，任务与费用尝试分开保存。
CREATE TABLE ai_task (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purpose VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    title VARCHAR(200) NOT NULL,
    provider VARCHAR(120) NOT NULL,
    model VARCHAR(200) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    manifest_json MEDIUMTEXT NOT NULL,
    manifest_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    configuration_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    authorization_epoch CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count INT NOT NULL,
    candidate_json MEDIUMTEXT NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    running_owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN status IN ('RUNNING','VALIDATING','CANCEL_REQUESTED') THEN owner_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_task_owner_id (owner_id,id),
    UNIQUE KEY uk_ai_task_running_owner (running_owner_id),
    KEY ix_ai_task_owner_status_created (owner_id,status,created_at,id),
    CONSTRAINT fk_ai_task_owner FOREIGN KEY (owner_id) REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_task_purpose CHECK (purpose='KNOWLEDGE_DRAFT'),
    CONSTRAINT ck_ai_task_status CHECK (status IN ('QUEUED','RUNNING','VALIDATING','SUCCEEDED','FAILED','UNKNOWN','CANCEL_REQUESTED','CANCELED')),
    CONSTRAINT ck_ai_task_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_ai_task_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_ai_task_attempt_count CHECK (attempt_count BETWEEN 1 AND 3),
    CONSTRAINT ck_ai_task_manifest CHECK (JSON_VALID(manifest_json) AND JSON_TYPE(CAST(manifest_json AS JSON))='OBJECT' AND OCTET_LENGTH(manifest_json)<=131072),
    CONSTRAINT ck_ai_task_candidate CHECK (
        (status='SUCCEEDED' AND candidate_json IS NOT NULL AND JSON_VALID(candidate_json)
            AND JSON_TYPE(CAST(candidate_json AS JSON))='OBJECT' AND OCTET_LENGTH(candidate_json)<=524288)
        OR (status<>'SUCCEEDED' AND candidate_json IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- current_attempt_id 与首个尝试存在插入顺序，服务在同一 owner 事务内维护，避免循环外键。
CREATE TABLE ai_attempt (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_number INT NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fee_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reserved_micros BIGINT NOT NULL,
    accounted_micros BIGINT NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    provider_request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reconciliation_note VARCHAR(1000) NULL,
    budget_date DATE NOT NULL,
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    runtime_epoch CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    sent_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_attempt_owner_task_id (owner_id,task_id,id),
    UNIQUE KEY uk_ai_attempt_number (task_id,attempt_number),
    KEY ix_ai_attempt_owner_fee_date_currency (owner_id,fee_status,budget_date,currency),
    CONSTRAINT fk_ai_attempt_task FOREIGN KEY (owner_id,task_id) REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_attempt_number CHECK (attempt_number BETWEEN 1 AND 3),
    CONSTRAINT ck_ai_attempt_status CHECK (status IN ('QUEUED','RUNNING','VALIDATING','SUCCEEDED','FAILED','UNKNOWN','CANCEL_REQUESTED','CANCELED')),
    CONSTRAINT ck_ai_attempt_fee_status CHECK (fee_status IN ('RESERVED','REPORTED','UNKNOWN','RELEASED','RECONCILED')),
    CONSTRAINT ck_ai_attempt_reservation CHECK (reserved_micros BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_ai_attempt_amount CHECK (
        (fee_status IN ('REPORTED','RECONCILED') AND accounted_micros IS NOT NULL AND accounted_micros BETWEEN 0 AND 9007199254740991)
        OR (fee_status IN ('RESERVED','UNKNOWN','RELEASED') AND accounted_micros IS NULL)),
    CONSTRAINT ck_ai_attempt_usage CHECK ((prompt_tokens IS NULL OR prompt_tokens>=0) AND (completion_tokens IS NULL OR completion_tokens>=0)),
    CONSTRAINT ck_ai_attempt_reconciliation CHECK (
        (fee_status='RECONCILED' AND reconciliation_note IS NOT NULL AND CHAR_LENGTH(TRIM(reconciliation_note)) BETWEEN 1 AND 1000)
        OR (fee_status<>'RECONCILED' AND reconciliation_note IS NULL)),
    CONSTRAINT ck_ai_attempt_claim CHECK ((claim_token IS NULL AND sent_at IS NULL) OR (claim_token IS NOT NULL AND sent_at IS NOT NULL)),
    CONSTRAINT ck_ai_attempt_lifecycle CHECK (
        (status='QUEUED' AND fee_status='RESERVED' AND claim_token IS NULL AND lease_until IS NULL AND finished_at IS NULL)
        OR (status IN ('RUNNING','VALIDATING','CANCEL_REQUESTED') AND fee_status='RESERVED'
            AND claim_token IS NOT NULL AND lease_until IS NOT NULL AND finished_at IS NULL)
        OR (status IN ('SUCCEEDED','FAILED','UNKNOWN','CANCELED') AND fee_status<>'RESERVED'
            AND lease_until IS NULL AND finished_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
