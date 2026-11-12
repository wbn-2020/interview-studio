-- 画布聚合与不可变修订分别保存；移出卡片只修改实例，不删除知识对象。
CREATE TABLE knowledge_board (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(200) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    snapshot_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_board_owner_id (owner_id, id),
    CONSTRAINT fk_board_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_board_kind CHECK (kind IN ('MIND_MAP', 'BOARD')),
    CONSTRAINT ck_board_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_board_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_board_json CHECK (JSON_VALID(snapshot_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_board_revision (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    board_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id, board_id, revision),
    CONSTRAINT fk_board_revision_board FOREIGN KEY (owner_id, board_id)
        REFERENCES knowledge_board (owner_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_board_snapshot_json CHECK (JSON_VALID(snapshot_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 引用快照绑定具体知识版本，便于后续生命周期影响查询；正文预览同时保存在修订 JSON。
CREATE TABLE knowledge_board_reference (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    board_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    board_revision BIGINT NOT NULL,
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (owner_id, board_id, board_revision, item_id),
    KEY ix_board_reference_item (owner_id, item_id, version_id),
    CONSTRAINT fk_board_reference_revision FOREIGN KEY (owner_id, board_id, board_revision)
        REFERENCES knowledge_board_revision (owner_id, board_id, revision) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_board_reference_version FOREIGN KEY (owner_id, item_id, version_id)
        REFERENCES knowledge_version (owner_id, item_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
