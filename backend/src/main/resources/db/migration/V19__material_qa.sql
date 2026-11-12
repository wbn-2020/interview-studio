-- 服务器显式升级；问答来源保留确切摘录身份，历史清单与回答仍由原任务保存。
ALTER TABLE ai_task
    DROP CHECK ck_ai_task_purpose_v16,
    ADD CONSTRAINT ck_ai_task_purpose_v19 CHECK (purpose IN ('KNOWLEDGE_DRAFT','QUESTION_DRAFT','FOLLOW_UP','ANSWER_EVALUATION','MATERIAL_QA'));

CREATE TABLE ai_material_qa_source (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    excerpt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    PRIMARY KEY (owner_id,task_id,excerpt_id),
    UNIQUE KEY uk_ai_material_qa_ordinal (owner_id,task_id,ordinal),
    KEY ix_ai_material_qa_excerpt (owner_id,excerpt_id,task_id),
    CONSTRAINT fk_ai_material_qa_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_material_qa_excerpt FOREIGN KEY (owner_id,excerpt_id)
        REFERENCES material_excerpt (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_material_qa_ordinal CHECK (ordinal BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
