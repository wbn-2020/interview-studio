-- 服务器发布时显式执行；此批只建立卡片与来源，不包含复习事件或排程状态。
CREATE TABLE memory_card (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    current_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_card_owner_id (owner_id,id),
    KEY ix_memory_card_owner_status_id (owner_id,status,id),
    CONSTRAINT fk_memory_card_owner FOREIGN KEY (owner_id) REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_memory_card_status CHECK (status IN ('ACTIVE','ARCHIVED')),
    CONSTRAINT ck_memory_card_revision CHECK (revision BETWEEN 0 AND 1000),
    CONSTRAINT ck_memory_card_version_number CHECK (version_number BETWEEN 1 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 当前指针由创建和保存事务维护，避免卡片与首版形成循环插入外键。
CREATE TABLE memory_card_version (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT NOT NULL,
    card_kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(200) NOT NULL,
    prompt_json MEDIUMTEXT NOT NULL,
    reference_text MEDIUMTEXT NOT NULL,
    content_bytes INT NOT NULL,
    source_count TINYINT NOT NULL,
    related_concept_count TINYINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_version_number (card_id,version_number),
    UNIQUE KEY uk_memory_version_owner_card (owner_id,card_id,id),
    KEY ix_memory_version_kind (owner_id,card_kind,card_id,id),
    CONSTRAINT fk_memory_version_card FOREIGN KEY (owner_id,card_id) REFERENCES memory_card (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_memory_version_number CHECK (version_number BETWEEN 1 AND 1000),
    CONSTRAINT ck_memory_version_kind CHECK (card_kind IN ('QA','CLOZE')),
    CONSTRAINT ck_memory_version_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_memory_prompt_json CHECK (JSON_VALID(prompt_json) AND JSON_TYPE(CAST(prompt_json AS JSON))='OBJECT' AND OCTET_LENGTH(prompt_json) <= 196608),
    CONSTRAINT ck_memory_reference_length CHECK (CHAR_LENGTH(reference_text) BETWEEN 1 AND 20000),
    CONSTRAINT ck_memory_content_bytes CHECK (content_bytes BETWEEN 1 AND 196608),
    CONSTRAINT ck_memory_source_count CHECK (source_count BETWEEN 1 AND 10),
    CONSTRAINT ck_memory_concept_count CHECK (related_concept_count BETWEEN 0 AND 20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE memory_card_source (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position_index INT NOT NULL,
    PRIMARY KEY (version_id,knowledge_item_id,knowledge_version_id),
    UNIQUE KEY uk_memory_source_position (version_id,position_index),
    KEY ix_memory_source_filter (owner_id,knowledge_item_id,card_id,version_id),
    CONSTRAINT fk_memory_source_card_version FOREIGN KEY (owner_id,card_id,version_id) REFERENCES memory_card_version (owner_id,card_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_memory_source_knowledge_version FOREIGN KEY (owner_id,knowledge_item_id,knowledge_version_id) REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_memory_source_position CHECK (position_index BETWEEN 0 AND 9)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 相关概念只表达结构归属；CONCEPT 类型由持有 owner 写锁的服务校验。
CREATE TABLE memory_card_concept (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    concept_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position_index INT NOT NULL,
    PRIMARY KEY (version_id,concept_id),
    UNIQUE KEY uk_memory_concept_position (version_id,position_index),
    KEY ix_memory_concept_filter (owner_id,concept_id,card_id,version_id),
    CONSTRAINT fk_memory_concept_card_version FOREIGN KEY (owner_id,card_id,version_id) REFERENCES memory_card_version (owner_id,card_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_memory_concept_knowledge FOREIGN KEY (owner_id,concept_id) REFERENCES knowledge_item (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_memory_concept_position CHECK (position_index BETWEEN 0 AND 19)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
