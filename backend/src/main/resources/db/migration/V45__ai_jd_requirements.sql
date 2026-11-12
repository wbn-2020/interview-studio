-- 岗位要求候选仅在人工选择后追加到目标新版本，既有要求身份和旧版本保持不变。
ALTER TABLE ai_task
    DROP CHECK ck_ai_task_purpose_v44,
    ADD CONSTRAINT ck_ai_task_purpose_v45 CHECK (purpose IN (
        'KNOWLEDGE_DRAFT','QUESTION_DRAFT','FOLLOW_UP','ANSWER_EVALUATION','MATERIAL_QA','EXPRESSION_DRAFT',
        'PREPARATION_PLAN','RELATION_DRAFT','ANSWER_COMPARISON','MEMORY_CARD_DRAFT','JD_REQUIREMENT_DRAFT'));

CREATE TABLE ai_jd_requirement_decision (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    requirements_json TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,task_id),
    UNIQUE KEY uk_ai_jd_requirement_target (owner_id,target_item_id,target_version_id),
    CONSTRAINT fk_ai_jd_requirement_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_jd_requirement_target FOREIGN KEY (owner_id,target_item_id,target_version_id)
        REFERENCES preparation_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_jd_requirement_links CHECK (JSON_VALID(requirements_json)
        AND JSON_TYPE(CAST(requirements_json AS JSON))='ARRAY' AND OCTET_LENGTH(requirements_json)<=8192),
    CONSTRAINT ck_ai_jd_requirement_state CHECK (
        (status='ADOPTED' AND target_item_id IS NOT NULL AND target_version_id IS NOT NULL
            AND JSON_LENGTH(requirements_json) BETWEEN 1 AND 20)
        OR (status='IGNORED' AND target_item_id IS NULL AND target_version_id IS NULL AND JSON_LENGTH(requirements_json)=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
