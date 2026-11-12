-- 事实候选固定资料或训练提交来源；人工采用只新建待核实事实，不改写经历和责任判断。
ALTER TABLE ai_task
    DROP CHECK ck_ai_task_purpose_v45,
    ADD CONSTRAINT ck_ai_task_purpose_v46 CHECK (purpose IN (
        'KNOWLEDGE_DRAFT','QUESTION_DRAFT','FOLLOW_UP','ANSWER_EVALUATION','MATERIAL_QA','EXPRESSION_DRAFT',
        'PREPARATION_PLAN','RELATION_DRAFT','ANSWER_COMPARISON','MEMORY_CARD_DRAFT','JD_REQUIREMENT_DRAFT','FACT_DRAFT'));

CREATE TABLE ai_fact_draft_source (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (owner_id,task_id),
    UNIQUE KEY uk_ai_fact_draft_kind (owner_id,task_id,kind),
    CONSTRAINT fk_ai_fact_draft_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_fact_draft_answer FOREIGN KEY (owner_id,session_id,question_id,answer_id)
        REFERENCES training_answer (owner_id,session_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_fact_draft_source CHECK (
        (kind='MATERIAL' AND session_id IS NULL AND question_id IS NULL AND answer_id IS NULL)
        OR (kind='TRAINING' AND session_id IS NOT NULL AND question_id IS NOT NULL AND answer_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_fact_draft_material (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    excerpt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    PRIMARY KEY (owner_id,task_id,excerpt_id),
    UNIQUE KEY uk_ai_fact_draft_material_ordinal (owner_id,task_id,ordinal),
    CONSTRAINT fk_ai_fact_draft_material_source FOREIGN KEY (owner_id,task_id,kind)
        REFERENCES ai_fact_draft_source (owner_id,task_id,kind) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_fact_draft_material_excerpt FOREIGN KEY (owner_id,excerpt_id)
        REFERENCES material_excerpt (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_fact_draft_material CHECK (kind='MATERIAL' AND ordinal BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_fact_draft_decision (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,task_id),
    UNIQUE KEY uk_ai_fact_draft_target (owner_id,target_item_id),
    CONSTRAINT fk_ai_fact_draft_decision_source FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_fact_draft_source (owner_id,task_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_fact_draft_project FOREIGN KEY (owner_id,target_project_id)
        REFERENCES preparation_item (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_fact_draft_target FOREIGN KEY (owner_id,target_item_id,target_version_id)
        REFERENCES preparation_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_fact_draft_decision CHECK (
        (status='ADOPTED' AND target_project_id IS NOT NULL AND target_item_id IS NOT NULL AND target_version_id IS NOT NULL)
        OR (status='IGNORED' AND target_project_id IS NULL AND target_item_id IS NULL AND target_version_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
