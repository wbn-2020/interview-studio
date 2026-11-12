-- 改写是确切提交的独立只读参考；不改写正式答案、评价决定或旧评价统计。
ALTER TABLE ai_task
    DROP CHECK ck_ai_task_purpose_v46,
    ADD CONSTRAINT ck_ai_task_purpose_v47 CHECK (purpose IN (
        'KNOWLEDGE_DRAFT','QUESTION_DRAFT','FOLLOW_UP','ANSWER_EVALUATION','MATERIAL_QA','EXPRESSION_DRAFT',
        'PREPARATION_PLAN','RELATION_DRAFT','ANSWER_COMPARISON','MEMORY_CARD_DRAFT','JD_REQUIREMENT_DRAFT','FACT_DRAFT',
        'ANSWER_REWRITE'));

CREATE TABLE ai_answer_rewrite (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,task_id),
    KEY ix_ai_answer_rewrite_session (owner_id,session_id,question_id,task_id),
    KEY ix_ai_answer_rewrite_answer (owner_id,answer_id,task_id),
    CONSTRAINT fk_ai_answer_rewrite_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_answer_rewrite_access FOREIGN KEY (owner_id,session_id,question_id,answer_id)
        REFERENCES ai_evaluation_access (owner_id,session_id,question_id,answer_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
