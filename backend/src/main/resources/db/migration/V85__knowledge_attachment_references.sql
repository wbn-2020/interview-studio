-- 正文中的私有附件链接／图片是结构引用，不等同于材料摘录、内容采用或 AI 读取授权。
CREATE TABLE knowledge_attachment_reference (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT NOT NULL,
    attachment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attachment_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal_number INT NOT NULL,
    reference_kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    line_start INT NOT NULL,
    line_end INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_attachment_ordinal (version_id,ordinal_number),
    KEY ix_knowledge_attachment_source (owner_id,item_id,version_id),
    KEY ix_knowledge_attachment_target (owner_id,attachment_id,id),
    CONSTRAINT fk_knowledge_attachment_version FOREIGN KEY (owner_id,item_id,version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_knowledge_attachment_target FOREIGN KEY (owner_id,attachment_id,attachment_sha256)
        REFERENCES material_attachment (owner_id,id,sha256) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_knowledge_attachment_number CHECK (version_number > 0),
    CONSTRAINT ck_knowledge_attachment_ordinal CHECK (ordinal_number BETWEEN 1 AND 200),
    CONSTRAINT ck_knowledge_attachment_kind CHECK (reference_kind IN ('LINK','IMAGE')),
    CONSTRAINT ck_knowledge_attachment_lines CHECK (line_start > 0 AND line_end >= line_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 零引用也有扫描记录；不向历史版本伪造“已经扫描”标记，由显式分批重建补齐。
CREATE TABLE knowledge_attachment_scan (
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parser_version VARCHAR(48) NOT NULL,
    reference_count INT NOT NULL,
    unresolved_count INT NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (version_id),
    KEY ix_knowledge_attachment_scan_owner (owner_id,version_id),
    CONSTRAINT fk_knowledge_attachment_scan_version FOREIGN KEY (owner_id,item_id,version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_knowledge_attachment_scan_count CHECK (reference_count BETWEEN 0 AND 200),
    CONSTRAINT ck_knowledge_attachment_scan_unresolved CHECK (unresolved_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
