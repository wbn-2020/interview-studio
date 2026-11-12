-- S1 已发布迁移保持不变；整理修订独立于正文、草稿与主题修订。
ALTER TABLE knowledge_outbox DROP CHECK ck_outbox_event;
ALTER TABLE knowledge_outbox ADD CONSTRAINT ck_outbox_event
    CHECK (event_type IN ('CREATED', 'PUBLISHED', 'ARCHIVED', 'RESTORED', 'METADATA'));

CREATE TABLE knowledge_organization (
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    aliases_json JSON NOT NULL,
    tags_json JSON NOT NULL,
    favorite BOOLEAN NOT NULL DEFAULT FALSE,
    main_note_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    visited_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (item_id),
    KEY ix_organization_owner_favorite_item (owner_id, favorite, item_id),
    KEY ix_organization_owner_visit_item (owner_id, visited_at DESC, item_id),
    CONSTRAINT fk_organization_item FOREIGN KEY (owner_id, item_id)
        REFERENCES knowledge_item (owner_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_organization_main_note FOREIGN KEY (owner_id, main_note_id)
        REFERENCES knowledge_item (owner_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_organization_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_organization_arrays CHECK (JSON_TYPE(aliases_json) = 'ARRAY' AND JSON_TYPE(tags_json) = 'ARRAY'),
    CONSTRAINT ck_organization_array_lengths CHECK (JSON_LENGTH(aliases_json) <= 20 AND JSON_LENGTH(tags_json) <= 20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_concept_note (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    concept_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    note_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_concept_note_owner_endpoints (owner_id, concept_id, note_id),
    KEY ix_concept_note_owner_note (owner_id, note_id, id),
    CONSTRAINT fk_concept_note_concept FOREIGN KEY (owner_id, concept_id)
        REFERENCES knowledge_item (owner_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_concept_note_note FOREIGN KEY (owner_id, note_id)
        REFERENCES knowledge_item (owner_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 引用固定源版本与原始行号；解析完成标记包含零引用版本，避免重建反复扫描。
CREATE TABLE knowledge_reference (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_number INT NOT NULL,
    target_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal_number INT NOT NULL,
    line_start INT NOT NULL,
    line_end INT NOT NULL,
    excerpt VARCHAR(300) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reference_version_ordinal (source_version_id, ordinal_number),
    KEY ix_reference_owner_source_version (owner_id, source_item_id, source_version_id, id),
    KEY ix_reference_owner_target_id (owner_id, target_item_id, id),
    CONSTRAINT fk_reference_source FOREIGN KEY (owner_id, source_item_id, source_version_id)
        REFERENCES knowledge_version (owner_id, item_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_reference_target FOREIGN KEY (owner_id, target_item_id)
        REFERENCES knowledge_item (owner_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_reference_lines CHECK (line_start > 0 AND line_end >= line_start),
    CONSTRAINT ck_reference_ordinal CHECK (ordinal_number BETWEEN 1 AND 200),
    CONSTRAINT ck_reference_version_number CHECK (source_version_number > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_reference_scan (
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parser_version VARCHAR(32) NOT NULL,
    unresolved_count INT NOT NULL DEFAULT 0,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (version_id),
    KEY ix_reference_scan_owner_version (owner_id, version_id),
    CONSTRAINT fk_reference_scan_version FOREIGN KEY (owner_id, item_id, version_id)
        REFERENCES knowledge_version (owner_id, item_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_reference_scan_unresolved CHECK (unresolved_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
