-- 服务器发布时显式执行；原知识任务和候选 JSON 保留，按 purpose 区分两种候选结构。
ALTER TABLE ai_task
    DROP CHECK ck_ai_task_purpose,
    ADD CONSTRAINT ck_ai_task_purpose_v14 CHECK (purpose IN ('KNOWLEDGE_DRAFT','QUESTION_DRAFT'));

-- 题库采用与知识采用分别保留目标外键；应用在同一 owner 命令中校验用途和唯一决定。
CREATE TABLE ai_question_decision (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,task_id),
    UNIQUE KEY uk_ai_question_decision_target (owner_id,target_question_id,target_version_id),
    CONSTRAINT fk_ai_question_decision_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_question_decision_target FOREIGN KEY (owner_id,target_question_id,target_version_id)
        REFERENCES question_version (owner_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_question_decision_state CHECK (
        (status='ADOPTED' AND target_question_id IS NOT NULL AND target_version_id IS NOT NULL)
        OR (status='IGNORED' AND target_question_id IS NULL AND target_version_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 记录本次外发的全部确切知识版本；不等同于用户给题目添加的知识关联。
CREATE TABLE ai_question_source (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_number INT NOT NULL,
    source_title VARCHAR(200) NOT NULL,
    ordinal INT NOT NULL,
    PRIMARY KEY (owner_id,task_id,source_item_id),
    UNIQUE KEY uk_ai_question_source_ordinal (owner_id,task_id,ordinal),
    KEY ix_ai_question_source_version (owner_id,source_item_id,source_version_id,task_id),
    CONSTRAINT fk_ai_question_source_decision FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_question_decision (owner_id,task_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_question_source_version FOREIGN KEY (owner_id,source_item_id,source_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_question_source_number CHECK (source_version_number > 0),
    CONSTRAINT ck_ai_question_source_title CHECK (CHAR_LENGTH(TRIM(source_title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_ai_question_source_ordinal CHECK (ordinal BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
