-- 跨域引用单独索引；旧知识外键及历史绑定保持原结构。
CREATE TABLE knowledge_board_external_reference (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    board_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    board_revision BIGINT NOT NULL,
    reference_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    attachment_revision BIGINT NULL,
    PRIMARY KEY (owner_id, board_id, board_revision, reference_kind, item_id),
    KEY ix_board_external_source (owner_id, reference_kind, item_id, version_id),
    CONSTRAINT fk_board_external_revision FOREIGN KEY (owner_id, board_id, board_revision)
        REFERENCES knowledge_board_revision (owner_id, board_id, revision) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_board_external_kind CHECK (
        (reference_kind = 'QUESTION' AND version_id IS NOT NULL AND attachment_revision IS NULL)
        OR (reference_kind = 'ATTACHMENT' AND version_id IS NULL AND attachment_revision IS NOT NULL
            AND attachment_revision BETWEEN 0 AND 9007199254740991)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
