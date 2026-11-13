-- 服务器显式迁移；业务包不包含导出作业、恢复守卫、会话、密钥或独立生命周期账本。
CREATE TABLE data_archive_job (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    format_version INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    byte_size BIGINT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    row_count BIGINT NULL,
    file_count INT NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ledger_sequence BIGINT NULL,
    ledger_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (id),
    KEY ix_archive_owner_time (owner_id,created_at,id),
    KEY ix_archive_queue (owner_id,state,created_at,id),
    CONSTRAINT fk_archive_owner FOREIGN KEY (owner_id) REFERENCES owner_workspace(owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_archive_state CHECK (state IN ('QUEUED','RUNNING','READY','FAILED','EXPIRED')),
    CONSTRAINT ck_archive_format CHECK (format_version=1),
    CONSTRAINT ck_archive_time CHECK (expires_at>created_at),
    CONSTRAINT ck_archive_result CHECK (state<>'READY' OR (byte_size>0 AND sha256 IS NOT NULL AND row_count>=0
        AND file_count>=0 AND ledger_sequence>=0 AND ledger_sha256 IS NOT NULL AND completed_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 无 owner 外键：空恢复环境在导入 owner_workspace 前也必须保持封锁；失败不会自动解除。
CREATE TABLE data_restore_gate (
    singleton_id TINYINT NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    archive_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    archive_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    restored_material_directory VARCHAR(2048) NULL,
    restored_index_directory VARCHAR(2048) NULL,
    ledger_sequence BIGINT NULL,
    ledger_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ai_blocked BOOLEAN NOT NULL DEFAULT FALSE,
    ack_ai_config_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ack_ai_config_version VARCHAR(80) NULL,
    ack_ai_config_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (singleton_id),
    CONSTRAINT ck_restore_singleton CHECK (singleton_id=1),
    CONSTRAINT ck_restore_state CHECK (state IN ('OPEN','SEALED','RESTORING','VERIFYING','FAILED')),
    CONSTRAINT ck_restore_ai CHECK (ai_blocked IN (0,1)),
    CONSTRAINT ck_restore_directories CHECK (
        (archive_id IS NULL AND restored_material_directory IS NULL AND restored_index_directory IS NULL)
        OR (archive_id IS NOT NULL AND restored_material_directory IS NOT NULL
            AND restored_index_directory IS NOT NULL)),
    CONSTRAINT ck_restore_ai_ack CHECK (
        (ack_ai_config_fingerprint IS NULL AND ack_ai_config_version IS NULL AND ack_ai_config_at IS NULL)
        OR (ack_ai_config_fingerprint IS NOT NULL AND ack_ai_config_fingerprint REGEXP '^[0-9a-f]{64}$'
            AND ack_ai_config_version IS NOT NULL AND CHAR_LENGTH(TRIM(ack_ai_config_version)) BETWEEN 1 AND 80
            AND ack_ai_config_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO data_restore_gate(singleton_id,state,ai_blocked,updated_at) VALUES(1,'OPEN',FALSE,UTC_TIMESTAMP(6));
