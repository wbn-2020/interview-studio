-- 在服务器发布步骤显式执行。来源元数据修订不改变知识正文、原始采用关系或模型授权。
CREATE TABLE knowledge_source_revision (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    sources_json JSON NOT NULL,
    saved_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,version_id,revision),
    CONSTRAINT fk_knowledge_source_version FOREIGN KEY (owner_id,item_id,version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_knowledge_source_revision CHECK (revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_knowledge_source_list CHECK (JSON_TYPE(sources_json)='ARRAY' AND JSON_LENGTH(sources_json)<=20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
