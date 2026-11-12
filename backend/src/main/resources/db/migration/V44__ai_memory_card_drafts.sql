-- 记忆卡候选只在显式采用后创建卡片；候选本身不启用排程或产生复习记录。
ALTER TABLE ai_task
    DROP CHECK ck_ai_task_purpose_v31,
    ADD CONSTRAINT ck_ai_task_purpose_v44 CHECK (purpose IN (
        'KNOWLEDGE_DRAFT','QUESTION_DRAFT','FOLLOW_UP','ANSWER_EVALUATION','MATERIAL_QA','EXPRESSION_DRAFT',
        'PREPARATION_PLAN','RELATION_DRAFT','ANSWER_COMPARISON','MEMORY_CARD_DRAFT'));

CREATE TABLE ai_card_draft_source (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_number INT NOT NULL,
    source_title VARCHAR(200) NOT NULL,
    source_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    PRIMARY KEY (owner_id,task_id,source_item_id),
    UNIQUE KEY uk_ai_card_source_ordinal (owner_id,task_id,ordinal),
    KEY ix_ai_card_source_version (owner_id,source_item_id,source_version_id,task_id),
    CONSTRAINT fk_ai_card_source_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_card_source_version FOREIGN KEY (owner_id,source_item_id,source_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_card_source_number CHECK (source_version_number > 0),
    CONSTRAINT ck_ai_card_source_title CHECK (CHAR_LENGTH(TRIM(source_title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_ai_card_source_kind CHECK (source_kind IN ('NOTE','CONCEPT')),
    CONSTRAINT ck_ai_card_source_ordinal CHECK (ordinal BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_card_draft_decision (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_card_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,task_id),
    UNIQUE KEY uk_ai_card_decision_target (owner_id,target_card_id,target_version_id),
    CONSTRAINT fk_ai_card_decision_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_card_decision_version FOREIGN KEY (owner_id,target_card_id,target_version_id)
        REFERENCES memory_card_version (owner_id,card_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_card_decision_state CHECK (
        (status='ADOPTED' AND target_card_id IS NOT NULL AND target_version_id IS NOT NULL)
        OR (status='IGNORED' AND target_card_id IS NULL AND target_version_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
