-- 由服务器发布步骤显式执行；应用启动不自动迁移，不在本机运行本文件。
CREATE TABLE owner_workspace (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    layout_revision BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_id),
    CONSTRAINT ck_workspace_layout_revision CHECK (layout_revision BETWEEN 0 AND 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 写命令、业务变更和回执在同一 owner 行锁及事务中提交，防止结果未知时重复创建。
CREATE TABLE command_receipt (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(255) NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id, idempotency_key),
    CONSTRAINT fk_receipt_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_receipt_json CHECK (JSON_VALID(response_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_item (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    metadata_revision BIGINT NOT NULL DEFAULT 0,
    current_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    version_number INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_item_owner_id (owner_id, id),
    KEY ix_item_owner_status_id (owner_id, status, id),
    KEY ix_item_owner_status_kind_id (owner_id, status, kind, id),
    CONSTRAINT fk_item_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_item_kind CHECK (kind IN ('NOTE', 'CONCEPT')),
    CONSTRAINT ck_item_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_item_metadata_revision CHECK (metadata_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_item_version_number CHECK (version_number > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 草稿修订号只用于编辑并发，不能替代可引用的不可变发布版本。
CREATE TABLE knowledge_draft (
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(200) NOT NULL,
    body MEDIUMTEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    saved_at DATETIME(6) NOT NULL,
    PRIMARY KEY (item_id),
    CONSTRAINT fk_draft_item FOREIGN KEY (item_id)
        REFERENCES knowledge_item (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_draft_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_draft_body CHECK (CHAR_LENGTH(body) <= 100000),
    CONSTRAINT ck_draft_revision CHECK (revision BETWEEN 0 AND 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_version (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    body MEDIUMTEXT NOT NULL,
    source_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'USER',
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_version_item_number (item_id, version_number),
    UNIQUE KEY uk_version_owner_item_id (owner_id, item_id, id),
    CONSTRAINT fk_version_owner_item FOREIGN KEY (owner_id, item_id)
        REFERENCES knowledge_item (owner_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_version_number CHECK (version_number > 0),
    CONSTRAINT ck_version_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_version_body CHECK (CHAR_LENGTH(body) <= 100000),
    CONSTRAINT ck_version_source CHECK (source_type = 'USER')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- current_version_id 由创建／发布事务写入；不建立与 version→item 相互依赖的循环外键。
CREATE TABLE knowledge_topic (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(64) NOT NULL,
    normalized_name VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    color CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_topic_owner_name (owner_id, normalized_name),
    KEY ix_topic_owner_name_id (owner_id, name, id),
    CONSTRAINT fk_topic_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_topic_name CHECK (CHAR_LENGTH(TRIM(name)) BETWEEN 1 AND 64),
    CONSTRAINT ck_topic_normalized_name CHECK (CHAR_LENGTH(normalized_name) BETWEEN 1 AND 64),
    CONSTRAINT ck_topic_color CHECK (REGEXP_LIKE(color, '^#[0-9A-Fa-f]{6}$', 'c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_item_topic (
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (item_id, topic_id),
    KEY ix_item_topic_topic_item (topic_id, item_id),
    CONSTRAINT fk_item_topic_item FOREIGN KEY (item_id)
        REFERENCES knowledge_item (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_item_topic_topic FOREIGN KEY (topic_id)
        REFERENCES knowledge_topic (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 对称边归一、自环和闭环由持有 owner 写锁的服务校验；组合外键约束两端归属。
CREATE TABLE knowledge_relation (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    relation_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_relation_owner_endpoints_type (owner_id, source_id, target_id, relation_type),
    KEY ix_relation_owner_target (owner_id, target_id, id),
    KEY ix_relation_owner_type (owner_id, relation_type, id),
    CONSTRAINT fk_relation_source FOREIGN KEY (owner_id, source_id)
        REFERENCES knowledge_item (owner_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_relation_target FOREIGN KEY (owner_id, target_id)
        REFERENCES knowledge_item (owner_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_relation_type CHECK (relation_type IN ('PRECEDES', 'PART_OF', 'APPLIED_IN', 'RELATED', 'CONTRASTS'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_position (
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    x DOUBLE NOT NULL,
    y DOUBLE NOT NULL,
    PRIMARY KEY (item_id),
    KEY ix_position_owner_item (owner_id, item_id),
    CONSTRAINT fk_position_owner_item FOREIGN KEY (owner_id, item_id)
        REFERENCES knowledge_item (owner_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_position_x CHECK (x BETWEEN -1000000 AND 1000000),
    CONSTRAINT ck_position_y CHECK (y BETWEEN -1000000 AND 1000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 索引只消费已提交的变更；失败不会回滚已保存正文，索引始终可由业务表重建。
CREATE TABLE knowledge_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    event_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY ix_outbox_pending (processed_at, id),
    KEY ix_outbox_owner_item (owner_id, item_id, id),
    KEY ix_outbox_owner_item_version (owner_id, item_id, version_id),
    CONSTRAINT fk_outbox_owner_item FOREIGN KEY (owner_id, item_id)
        REFERENCES knowledge_item (owner_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_outbox_version FOREIGN KEY (owner_id, item_id, version_id)
        REFERENCES knowledge_version (owner_id, item_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_outbox_event CHECK (event_type IN ('CREATED', 'PUBLISHED', 'ARCHIVED', 'RESTORED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
