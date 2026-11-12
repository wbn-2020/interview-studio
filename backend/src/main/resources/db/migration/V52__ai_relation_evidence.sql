-- 关系候选固定资料或训练来源身份，原文保留在任务清单；不创建正式关系或反馈决定。
CREATE TABLE ai_relation_evidence_source (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (owner_id,task_id),
    UNIQUE KEY uk_ai_relation_evidence_kind (owner_id,task_id,kind),
    CONSTRAINT fk_ai_relation_evidence_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_relation_evidence_answer FOREIGN KEY (owner_id,session_id,question_id,answer_id)
        REFERENCES training_answer (owner_id,session_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_relation_evidence_source CHECK (
        (kind='MATERIAL' AND session_id IS NULL AND question_id IS NULL AND answer_id IS NULL)
        OR (kind='TRAINING' AND session_id IS NOT NULL AND question_id IS NOT NULL AND answer_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_relation_evidence_material (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    excerpt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    PRIMARY KEY (owner_id,task_id,excerpt_id),
    UNIQUE KEY uk_ai_relation_evidence_material_ordinal (owner_id,task_id,ordinal),
    CONSTRAINT fk_ai_relation_evidence_material_source FOREIGN KEY (owner_id,task_id,kind)
        REFERENCES ai_relation_evidence_source (owner_id,task_id,kind) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_relation_evidence_material_excerpt FOREIGN KEY (owner_id,excerpt_id)
        REFERENCES material_excerpt (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_relation_evidence_material CHECK (kind='MATERIAL' AND ordinal BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
