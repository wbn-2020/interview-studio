-- 服务器发布时显式执行；题单与路线固定题目版本，步骤和依赖随集合正文一起形成不可变历史。
CREATE TABLE question_collection (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    current_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT NOT NULL,
    step_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_collection_owner_id (owner_id,id),
    KEY ix_collection_owner_status_id (owner_id,status,id),
    KEY ix_collection_owner_status_kind_id (owner_id,status,kind,id),
    CONSTRAINT fk_collection_owner FOREIGN KEY (owner_id) REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_collection_kind CHECK (kind IN ('LIST','ROUTE')),
    CONSTRAINT ck_collection_status CHECK (status IN ('ACTIVE','ARCHIVED')),
    CONSTRAINT ck_collection_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_collection_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_collection_number CHECK (version_number > 0),
    CONSTRAINT ck_collection_step_count CHECK (step_count BETWEEN 0 AND 50)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 当前版本指针由同一事务维护，避免身份与首版之间形成插入循环外键。
CREATE TABLE question_collection_version (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    collection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    content_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_collection_version_number (collection_id,version_number),
    UNIQUE KEY uk_collection_version_owner_id (owner_id,collection_id,id),
    CONSTRAINT fk_collection_version_collection FOREIGN KEY (owner_id,collection_id) REFERENCES question_collection (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_collection_version_number CHECK (version_number > 0),
    CONSTRAINT ck_collection_version_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_collection_version_json CHECK (JSON_VALID(content_json) AND JSON_TYPE(CAST(content_json AS JSON))='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE question_collection_step (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    collection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    step_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position_index INT NOT NULL,
    purpose VARCHAR(1000) NOT NULL,
    checkpoint VARCHAR(1000) NOT NULL,
    PRIMARY KEY (version_id,step_id),
    UNIQUE KEY uk_collection_step_owner_id (owner_id,collection_id,version_id,step_id),
    UNIQUE KEY uk_collection_step_question (version_id,question_id),
    UNIQUE KEY uk_collection_step_position (version_id,position_index),
    KEY ix_collection_step_question_version (owner_id,question_id,question_version_id,collection_id),
    CONSTRAINT fk_collection_step_collection FOREIGN KEY (owner_id,collection_id,version_id) REFERENCES question_collection_version (owner_id,collection_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_collection_step_question FOREIGN KEY (owner_id,question_id,question_version_id) REFERENCES question_version (owner_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_collection_step_position CHECK (position_index BETWEEN 0 AND 49)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 两端共同绑定 owner、集合与版本；不能连接其他集合或历史版本中的同名步骤。
CREATE TABLE question_collection_dependency (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    collection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    step_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    depends_on_step_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (version_id,step_id,depends_on_step_id),
    KEY ix_collection_dependency_step (owner_id,collection_id,version_id,step_id),
    KEY ix_collection_dependency_required (owner_id,collection_id,version_id,depends_on_step_id),
    CONSTRAINT fk_collection_dependency_step FOREIGN KEY (owner_id,collection_id,version_id,step_id) REFERENCES question_collection_step (owner_id,collection_id,version_id,step_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_collection_dependency_required FOREIGN KEY (owner_id,collection_id,version_id,depends_on_step_id) REFERENCES question_collection_step (owner_id,collection_id,version_id,step_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_collection_dependency_other CHECK (step_id <> depends_on_step_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
