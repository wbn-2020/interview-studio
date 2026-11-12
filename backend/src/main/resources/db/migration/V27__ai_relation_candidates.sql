-- 服务器显式升级；来源在任务创建时固定，逐项采用与正式关系写入同一 owner 事务。
ALTER TABLE ai_task
    DROP CHECK ck_ai_task_purpose_v24,
    ADD CONSTRAINT ck_ai_task_purpose_v27 CHECK (purpose IN (
        'KNOWLEDGE_DRAFT','QUESTION_DRAFT','FOLLOW_UP','ANSWER_EVALUATION','MATERIAL_QA','EXPRESSION_DRAFT','PREPARATION_PLAN','RELATION_DRAFT'));

CREATE TABLE ai_relation_source (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_number INT NOT NULL,
    source_title VARCHAR(200) NOT NULL,
    ordinal INT NOT NULL,
    PRIMARY KEY (owner_id,task_id,source_item_id),
    UNIQUE KEY uk_ai_relation_source_ordinal (owner_id,task_id,ordinal),
    KEY ix_ai_relation_source_version (owner_id,source_item_id,source_version_id,task_id),
    CONSTRAINT fk_ai_relation_source_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_relation_source_item FOREIGN KEY (owner_id,source_item_id)
        REFERENCES knowledge_item (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_relation_source_version FOREIGN KEY (owner_id,source_item_id,source_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_relation_source_kind CHECK (source_kind IN ('NOTE','CONCEPT')),
    CONSTRAINT ck_ai_relation_source_number CHECK (source_version_number > 0),
    CONSTRAINT ck_ai_relation_source_title CHECK (CHAR_LENGTH(TRIM(source_title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_ai_relation_source_ordinal CHECK (ordinal BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_relation_decision (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_id VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    relation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    content_json MEDIUMTEXT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,task_id,candidate_id),
    -- 正式关系可删除；决定独立保留原关系身份与最终采用内容。
    UNIQUE KEY uk_ai_relation_decision_relation (owner_id,relation_id),
    CONSTRAINT fk_ai_relation_decision_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_relation_decision_candidate CHECK (
        (CHAR_LENGTH(candidate_id)=2 AND candidate_id IN ('r1','r2','r3','r4','r5','r6','r7','r8','r9')) OR candidate_id='r10'),
    -- 1000 个 UTF-16 码元经 JSON 转义可占 6000 字节，另为端点与类型保留空间。
    CONSTRAINT ck_ai_relation_decision_content CHECK (
        content_json IS NULL OR (JSON_VALID(content_json)
            AND JSON_TYPE(CAST(content_json AS JSON))='OBJECT' AND OCTET_LENGTH(content_json)<=8192)),
    CONSTRAINT ck_ai_relation_decision_state CHECK (
        (status='ADOPTED' AND relation_id IS NOT NULL AND content_json IS NOT NULL)
        OR (status='IGNORED' AND relation_id IS NULL AND content_json IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
