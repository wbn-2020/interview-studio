-- 服务器发布时显式执行；题干与参考独立列存储，普通 GET 不读取隐藏参考。
CREATE TABLE question (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    favorite BOOLEAN NOT NULL DEFAULT FALSE,
    current_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_question_owner_id (owner_id,id),
    KEY ix_question_owner_status_id (owner_id,status,id),
    KEY ix_question_owner_favorite_status_id (owner_id,favorite,status,id),
    CONSTRAINT fk_question_owner FOREIGN KEY (owner_id) REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_question_status CHECK (status IN ('ACTIVE','ARCHIVED')),
    CONSTRAINT ck_question_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_question_favorite CHECK (favorite IN (0,1)),
    CONSTRAINT ck_question_number CHECK (version_number > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE question_version (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    question_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    difficulty TINYINT NULL,
    scenario VARCHAR(2000) NOT NULL,
    prompt_json MEDIUMTEXT NOT NULL,
    standard_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    has_reference BOOLEAN NOT NULL,
    reference_answer MEDIUMTEXT NOT NULL,
    checkpoints_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_question_version_number (question_id,version_number),
    UNIQUE KEY uk_question_version_owner_question (owner_id,question_id,id),
    KEY ix_question_version_type_difficulty (owner_id,question_type,difficulty,question_id,id),
    CONSTRAINT fk_question_version_question FOREIGN KEY (owner_id,question_id) REFERENCES question (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_question_version_number CHECK (version_number > 0),
    CONSTRAINT ck_question_version_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_question_version_type CHECK (question_type IN ('EXPLAIN','CODE_READING','DEBUG','PROJECT','TRADEOFF','SYSTEM_DESIGN','BEHAVIOR')),
    CONSTRAINT ck_question_version_difficulty CHECK (difficulty IS NULL OR difficulty BETWEEN 1 AND 5),
    CONSTRAINT ck_question_prompt_json CHECK (JSON_VALID(prompt_json) AND JSON_TYPE(CAST(prompt_json AS JSON))='OBJECT'),
    CONSTRAINT ck_question_standard_status CHECK (standard_status='PERSONAL_UNREVIEWED'),
    CONSTRAINT ck_question_reference_flag CHECK (has_reference IN (0,1)),
    CONSTRAINT ck_question_reference_length CHECK (CHAR_LENGTH(reference_answer) <= 20000),
    CONSTRAINT ck_question_checkpoints_json CHECK (JSON_VALID(checkpoints_json) AND JSON_TYPE(CAST(checkpoints_json AS JSON))='ARRAY' AND JSON_LENGTH(checkpoints_json) <= 30)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 当前版本指针由同一事务维护；避免身份与首个版本形成插入循环外键。
-- 新关联以 owner 复合外键约束知识主题归属，既有主题表内容不重写。
ALTER TABLE knowledge_topic ADD UNIQUE KEY uk_topic_owner_id (owner_id,id);

CREATE TABLE question_version_topic (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position_index INT NOT NULL,
    PRIMARY KEY (version_id,topic_id),
    UNIQUE KEY uk_question_topic_position (version_id,position_index),
    KEY ix_question_topic_filter (owner_id,topic_id,question_id,version_id),
    CONSTRAINT fk_question_topic_version FOREIGN KEY (owner_id,question_id,version_id) REFERENCES question_version (owner_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_question_topic_topic FOREIGN KEY (owner_id,topic_id) REFERENCES knowledge_topic (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_question_topic_position CHECK (position_index BETWEEN 0 AND 19)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE question_version_dimension (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dimension VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (version_id,dimension),
    KEY ix_question_dimension_filter (owner_id,dimension,question_id,version_id),
    CONSTRAINT fk_question_dimension_version FOREIGN KEY (owner_id,question_id,version_id) REFERENCES question_version (owner_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_question_dimension CHECK (dimension IN ('EXPLAIN','DEBUG','TRADEOFF','OWNERSHIP','COMMUNICATION','OTHER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE question_knowledge_reference (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position_index INT NOT NULL,
    PRIMARY KEY (version_id,knowledge_item_id,knowledge_version_id),
    UNIQUE KEY uk_question_knowledge_position (version_id,position_index),
    KEY ix_question_knowledge_filter (owner_id,knowledge_item_id,question_id,version_id),
    CONSTRAINT fk_question_knowledge_target FOREIGN KEY (owner_id,question_id,version_id) REFERENCES question_version (owner_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_question_knowledge_source FOREIGN KEY (owner_id,knowledge_item_id,knowledge_version_id) REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_question_knowledge_position CHECK (position_index BETWEEN 0 AND 19)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- AUTHORED 与 REFERENCE_VIEW 分开记载；事件只证明操作背景，不证明掌握或实际阅读。
CREATE TABLE question_exposure (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    exposure_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_question_exposure_history (owner_id,question_id,created_at,id),
    KEY ix_question_exposure_version (owner_id,question_id,version_id,created_at,id),
    CONSTRAINT fk_question_exposure_version FOREIGN KEY (owner_id,question_id,version_id) REFERENCES question_version (owner_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_question_exposure_kind CHECK (exposure_kind IN ('AUTHORED','REFERENCE_VIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
