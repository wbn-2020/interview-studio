-- 由服务器发布步骤显式应用；批次只留元数据，原文件及待确认正文仍由用户提供。
CREATE TABLE knowledge_text_import_batch (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    file_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_text_batch_owner_id (owner_id, id),
    KEY ix_text_batch_page (owner_id, created_at, id),
    CONSTRAINT fk_text_batch_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_text_batch_count CHECK (file_count BETWEEN 1 AND 20),
    CONSTRAINT ck_text_batch_revision CHECK (revision BETWEEN 0 AND file_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_text_import_batch_entry (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    file_name VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_bytes INT NOT NULL,
    source_characters INT NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    import_record_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_text_batch_entry_owner_id (owner_id, id),
    UNIQUE KEY uk_text_batch_entry_ordinal (owner_id, batch_id, ordinal),
    UNIQUE KEY uk_text_batch_entry_name (owner_id, batch_id, file_name),
    UNIQUE KEY uk_text_batch_entry_import (owner_id, import_record_id),
    CONSTRAINT fk_text_batch_entry_batch FOREIGN KEY (owner_id, batch_id)
        REFERENCES knowledge_text_import_batch (owner_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_text_batch_entry_import FOREIGN KEY (owner_id, import_record_id)
        REFERENCES knowledge_text_import (owner_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_text_batch_entry_ordinal CHECK (ordinal BETWEEN 1 AND 20),
    CONSTRAINT ck_text_batch_entry_name CHECK (CHAR_LENGTH(file_name) BETWEEN 1 AND 200),
    CONSTRAINT ck_text_batch_entry_hash CHECK (REGEXP_LIKE(source_sha256, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_text_batch_entry_bytes CHECK (source_bytes BETWEEN 1 AND 400000),
    CONSTRAINT ck_text_batch_entry_characters CHECK (source_characters BETWEEN 1 AND 100000),
    CONSTRAINT ck_text_batch_entry_state CHECK (state IN ('PENDING', 'IMPORTED', 'SKIPPED')),
    CONSTRAINT ck_text_batch_entry_result CHECK (
        (state='IMPORTED' AND import_record_id IS NOT NULL)
        OR (state IN ('PENDING', 'SKIPPED') AND import_record_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
