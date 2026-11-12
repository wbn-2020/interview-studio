-- 比较候选只绑定确切双答案对照；结果继续使用任务 JSON，不能直接写入人工来源判断。
ALTER TABLE ai_task
    DROP CHECK ck_ai_task_purpose_v27,
    ADD CONSTRAINT ck_ai_task_purpose_v31 CHECK (purpose IN (
        'KNOWLEDGE_DRAFT','QUESTION_DRAFT','FOLLOW_UP','ANSWER_EVALUATION','MATERIAL_QA','EXPRESSION_DRAFT',
        'PREPARATION_PLAN','RELATION_DRAFT','ANSWER_COMPARISON'));

CREATE TABLE ai_comparison (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    comparison_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,task_id),
    KEY ix_ai_comparison_page (owner_id,comparison_id,task_id),
    CONSTRAINT fk_ai_comparison_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_comparison_review FOREIGN KEY (owner_id,comparison_id)
        REFERENCES review_comparison (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
