-- 服务器显式升级；表达候选沿用任务与事实来源，采用决定固定准备域的确切表达版本。
ALTER TABLE ai_task
    DROP CHECK ck_ai_task_purpose_v19,
    ADD CONSTRAINT ck_ai_task_purpose_v22 CHECK (purpose IN (
        'KNOWLEDGE_DRAFT','QUESTION_DRAFT','FOLLOW_UP','ANSWER_EVALUATION','MATERIAL_QA','EXPRESSION_DRAFT'));

CREATE TABLE ai_expression_decision (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,task_id),
    UNIQUE KEY uk_ai_expression_decision_target (owner_id,target_item_id,target_version_id),
    CONSTRAINT fk_ai_expression_decision_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_expression_decision_target FOREIGN KEY (owner_id,target_item_id,target_version_id)
        REFERENCES preparation_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_expression_decision_state CHECK (
        (status='ADOPTED' AND mode IS NOT NULL AND mode IN ('NEW','EXISTING')
            AND target_item_id IS NOT NULL AND target_version_id IS NOT NULL)
        OR (status='IGNORED' AND mode IS NULL AND target_item_id IS NULL AND target_version_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
