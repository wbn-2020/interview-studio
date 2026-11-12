-- 仅由服务器发布步骤显式执行；准备内容与知识正文使用独立身份和版本。
CREATE TABLE preparation_item (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    verification VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    favorite BOOLEAN NOT NULL DEFAULT FALSE,
    current_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_preparation_owner_item (owner_id,id),
    KEY ix_preparation_owner_status_kind_id (owner_id,status,kind,id),
    KEY ix_preparation_owner_project_id (owner_id,project_id,id),
    KEY ix_preparation_owner_verification_id (owner_id,kind,verification,status,id),
    KEY ix_preparation_owner_favorite_id (owner_id,favorite,status,id),
    CONSTRAINT fk_preparation_owner FOREIGN KEY (owner_id) REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_preparation_project FOREIGN KEY (owner_id,project_id) REFERENCES preparation_item (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_preparation_kind CHECK (kind IN ('TARGET','PROJECT','FACT','EXPRESSION')),
    CONSTRAINT ck_preparation_status CHECK (status IN ('ACTIVE','ARCHIVED')),
    CONSTRAINT ck_preparation_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_preparation_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_preparation_number CHECK (version_number > 0),
    CONSTRAINT ck_preparation_fact CHECK (
      (kind='FACT' AND project_id IS NOT NULL AND verification IS NOT NULL AND verification IN ('UNVERIFIED','CONFIRMED','WITHDRAWN'))
      OR (kind<>'FACT' AND project_id IS NULL AND verification IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE preparation_version (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    content_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_preparation_version_number (item_id,version_number),
    UNIQUE KEY uk_preparation_version_owner_item (owner_id,item_id,id),
    CONSTRAINT fk_preparation_version_item FOREIGN KEY (owner_id,item_id) REFERENCES preparation_item (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_preparation_version_number CHECK (version_number > 0),
    CONSTRAINT ck_preparation_version_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_preparation_version_json CHECK (JSON_VALID(content_json) AND JSON_TYPE(CAST(content_json AS JSON))='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 条件外键分别绑定准备版本和知识版本；类型、归属及对象与版本匹配在服务中再次核验。
CREATE TABLE preparation_reference (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preparation_source_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    preparation_source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    knowledge_source_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    knowledge_source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    use_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_preparation_reference_prep (target_version_id,source_kind,preparation_source_item_id,preparation_source_version_id),
    UNIQUE KEY uk_preparation_reference_knowledge (target_version_id,source_kind,knowledge_source_item_id,knowledge_source_version_id),
    KEY ix_preparation_reference_target (owner_id,target_version_id),
    KEY ix_preparation_reference_impact (owner_id,preparation_source_item_id,target_item_id),
    CONSTRAINT fk_preparation_reference_target FOREIGN KEY (owner_id,target_item_id,target_version_id) REFERENCES preparation_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_preparation_reference_prep FOREIGN KEY (owner_id,preparation_source_item_id,preparation_source_version_id) REFERENCES preparation_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_preparation_reference_knowledge FOREIGN KEY (owner_id,knowledge_source_item_id,knowledge_source_version_id) REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_preparation_reference_source CHECK (
      (source_kind IN ('PROJECT','FACT') AND preparation_source_item_id IS NOT NULL AND preparation_source_version_id IS NOT NULL AND knowledge_source_item_id IS NULL AND knowledge_source_version_id IS NULL)
      OR (source_kind='KNOWLEDGE' AND preparation_source_item_id IS NULL AND preparation_source_version_id IS NULL AND knowledge_source_item_id IS NOT NULL AND knowledge_source_version_id IS NOT NULL)),
    CONSTRAINT ck_preparation_reference_use CHECK (use_type='ORGANIZATION' OR (use_type='FACT_BASIS' AND source_kind='FACT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE preparation_fact_decision (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_preparation_fact_decision_item (owner_id,item_id,created_at,id),
    CONSTRAINT fk_preparation_decision_version FOREIGN KEY (owner_id,item_id,version_id) REFERENCES preparation_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_preparation_decision CHECK (decision IN ('CONFIRMED','WITHDRAWN')),
    CONSTRAINT ck_preparation_withdraw_reason CHECK (decision<>'WITHDRAWN' OR CHAR_LENGTH(TRIM(reason)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE preparation_focus (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_id),
    CONSTRAINT fk_preparation_focus_owner FOREIGN KEY (owner_id) REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_preparation_focus_target FOREIGN KEY (owner_id,target_id) REFERENCES preparation_item (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_preparation_focus_revision CHECK (revision BETWEEN 0 AND 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
