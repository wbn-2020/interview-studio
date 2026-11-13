-- 知识包只登记原文指纹、用户决定与映射。正文仅在确认提交时写入确切首版。
CREATE TABLE knowledge_package (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    package_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    plan_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    create_copy BOOLEAN NOT NULL,
    import_count INT NOT NULL,
    skip_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    committed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_package_owner_id (owner_id, id),
    UNIQUE KEY uk_package_original (owner_id, original_key),
    KEY ix_package_page (owner_id, created_at, id),
    KEY ix_package_identity (owner_id, package_key),
    CONSTRAINT fk_package_owner FOREIGN KEY (owner_id)
      REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_package_hash CHECK (REGEXP_LIKE(package_key, '^[0-9a-f]{64}$', 'c')
      AND REGEXP_LIKE(plan_sha256, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_package_original CHECK ((create_copy=FALSE AND original_key IS NOT NULL AND original_key=package_key)
      OR (create_copy=TRUE AND original_key IS NULL)),
    CONSTRAINT ck_package_count CHECK (import_count BETWEEN 1 AND 20 AND skip_count BETWEEN 0 AND 19
      AND import_count+skip_count BETWEEN 1 AND 20),
    CONSTRAINT ck_package_state CHECK ((state='REGISTERED' AND revision=0 AND committed_at IS NULL)
      OR (state='COMMITTED' AND revision=1 AND committed_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_package_entry (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    package_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    source_id VARCHAR(600) COLLATE utf8mb4_0900_bin NOT NULL,
    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_bytes INT NOT NULL,
    source_characters INT NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(200) NOT NULL,
    tags_json JSON NOT NULL,
    aliases_json JSON NOT NULL,
    topic_ids_json JSON NOT NULL,
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (owner_id, package_id, ordinal),
    UNIQUE KEY uk_package_entry_source (owner_id, package_id, source_id),
    UNIQUE KEY uk_package_entry_item (item_id),
    UNIQUE KEY uk_package_entry_version (version_id),
    CONSTRAINT fk_package_entry_package FOREIGN KEY (owner_id, package_id)
      REFERENCES knowledge_package (owner_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_package_entry_ordinal CHECK (ordinal BETWEEN 1 AND 20),
    CONSTRAINT ck_package_entry_source CHECK (CHAR_LENGTH(source_id) BETWEEN 1 AND 600),
    CONSTRAINT ck_package_entry_hash CHECK (REGEXP_LIKE(source_sha256, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_package_entry_size CHECK (source_bytes BETWEEN 1 AND 400000
      AND source_characters BETWEEN 1 AND 100000),
    CONSTRAINT ck_package_entry_kind CHECK (kind IN ('NOTE', 'CONCEPT')),
    CONSTRAINT ck_package_entry_choice CHECK ((decision='IMPORT' AND item_id IS NOT NULL AND version_id IS NOT NULL)
      OR (decision='SKIP' AND item_id IS NULL AND version_id IS NULL)),
    CONSTRAINT ck_package_entry_metadata CHECK (CHAR_LENGTH(title) BETWEEN 1 AND 200
      AND JSON_TYPE(tags_json)='ARRAY' AND JSON_LENGTH(tags_json) BETWEEN 0 AND 20
      AND JSON_TYPE(aliases_json)='ARRAY' AND JSON_LENGTH(aliases_json) BETWEEN 0 AND 20
      AND JSON_TYPE(topic_ids_json)='ARRAY' AND JSON_LENGTH(topic_ids_json) BETWEEN 0 AND 20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_package_attachment (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    package_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    source_id VARCHAR(600) COLLATE utf8mb4_0900_bin NOT NULL,
    attachment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (owner_id, package_id, ordinal),
    UNIQUE KEY uk_package_attachment_source (owner_id, package_id, source_id),
    CONSTRAINT fk_package_attachment_package FOREIGN KEY (owner_id, package_id)
      REFERENCES knowledge_package (owner_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_package_attachment_ordinal CHECK (ordinal BETWEEN 1 AND 20),
    CONSTRAINT ck_package_attachment_source CHECK (CHAR_LENGTH(source_id) BETWEEN 1 AND 600),
    CONSTRAINT ck_package_attachment_hash CHECK (REGEXP_LIKE(source_sha256, '^[0-9a-f]{64}$', 'c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
