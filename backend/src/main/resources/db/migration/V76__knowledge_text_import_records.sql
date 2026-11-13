-- 由服务器发布步骤显式应用；本机不执行。只登记本迁移后显式文本导入，不推断旧知识的文件身份。
CREATE TABLE knowledge_text_import (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    file_name VARCHAR(200) NOT NULL,
    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_bytes INT NOT NULL,
    source_characters INT NOT NULL,
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    create_copy BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_text_import_owner_id (owner_id, id),
    UNIQUE KEY uk_text_import_target (owner_id, item_id),
    KEY ix_text_import_source (owner_id, source_sha256, created_at, id),
    KEY ix_text_import_page (owner_id, created_at, id),
    CONSTRAINT fk_text_import_version FOREIGN KEY (owner_id, item_id, version_id)
        REFERENCES knowledge_version (owner_id, item_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_text_import_hash CHECK (REGEXP_LIKE(source_sha256, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_text_import_bytes CHECK (source_bytes BETWEEN 1 AND 400000),
    CONSTRAINT ck_text_import_characters CHECK (source_characters BETWEEN 1 AND 100000),
    CONSTRAINT ck_text_import_file_name CHECK (CHAR_LENGTH(file_name) BETWEEN 1 AND 200),
    CONSTRAINT ck_text_import_copy CHECK (create_copy IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
