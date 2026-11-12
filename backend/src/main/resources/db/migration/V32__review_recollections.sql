-- 服务器显式迁移；回忆内容只追加版本，题目来源固定在采用时的两端版本。
CREATE TABLE review_recollection (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    current_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_recollection_owner (owner_id,id),
    KEY ix_recollection_status_page (owner_id,status,id),
    CONSTRAINT fk_recollection_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_recollection_status CHECK (status IN ('ACTIVE','ARCHIVED')),
    CONSTRAINT ck_recollection_revision CHECK (revision BETWEEN 0 AND 1000),
    CONSTRAINT ck_recollection_number CHECK (version_number BETWEEN 1 AND 1000 AND version_number<=revision+1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 当前版本指针由同一 owner 命令事务维护，读取时核对版本归属和序号，避免首版插入循环外键。
CREATE TABLE review_recollection_version (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recollection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    baseline_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_json MEDIUMTEXT NOT NULL,
    interview_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    interview_revision BIGINT NULL,
    interview_json TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_recollection_version_owner (owner_id,recollection_id,id),
    UNIQUE KEY uk_recollection_version_number (owner_id,recollection_id,version_number),
    KEY ix_recollection_version_interview (owner_id,interview_id,recollection_id,id),
    CONSTRAINT fk_recollection_version_item FOREIGN KEY (owner_id,recollection_id)
        REFERENCES review_recollection (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_recollection_version_interview FOREIGN KEY (owner_id,interview_id,interview_revision)
        REFERENCES interview_event_history (owner_id,event_id,revision) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_recollection_version_number CHECK (version_number BETWEEN 1 AND 1000),
    CONSTRAINT ck_recollection_version_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_recollection_version_baseline CHECK (baseline_kind IN ('RECOLLECTION','NONE')),
    CONSTRAINT ck_recollection_version_content CHECK (JSON_VALID(content_json)
        AND JSON_TYPE(CAST(content_json AS JSON))='OBJECT' AND JSON_LENGTH(content_json)=9
        AND OCTET_LENGTH(content_json)<=196608),
    CONSTRAINT ck_recollection_content_identity CHECK (COALESCE(
        JSON_TYPE(JSON_EXTRACT(content_json,'$.title'))='STRING'
        AND JSON_UNQUOTE(JSON_EXTRACT(content_json,'$.title'))=title
        AND JSON_TYPE(JSON_EXTRACT(content_json,'$.baselineKind'))='STRING'
        AND JSON_UNQUOTE(JSON_EXTRACT(content_json,'$.baselineKind'))=baseline_kind
        AND JSON_TYPE(JSON_EXTRACT(content_json,'$.answerFragment'))='STRING'
        AND ((baseline_kind='NONE' AND CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(content_json,'$.answerFragment')))=0)
            OR (baseline_kind='RECOLLECTION' AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(content_json,'$.answerFragment'))))>0)),FALSE)),
    CONSTRAINT ck_recollection_version_interview CHECK (
        (interview_id IS NULL AND interview_revision IS NULL AND interview_json IS NULL)
        OR (interview_id IS NOT NULL AND interview_revision IS NOT NULL
            AND interview_revision BETWEEN 0 AND 9007199254740991 AND interview_json IS NOT NULL
            AND JSON_VALID(interview_json) AND JSON_TYPE(CAST(interview_json AS JSON))='OBJECT'
            AND OCTET_LENGTH(interview_json)<=8192))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE review_recollection_question (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recollection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recollection_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,recollection_id,recollection_version_id),
    UNIQUE KEY uk_recollection_question_version (owner_id,question_id,question_version_id),
    CONSTRAINT fk_recollection_question_source FOREIGN KEY (owner_id,recollection_id,recollection_version_id)
        REFERENCES review_recollection_version (owner_id,recollection_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_recollection_question_target FOREIGN KEY (owner_id,question_id,question_version_id)
        REFERENCES question_version (owner_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
