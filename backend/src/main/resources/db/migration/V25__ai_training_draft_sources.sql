-- 服务器显式升级；训练片段使用确切提交关系，不能将训练 ID 写入知识版本来源。
CREATE TABLE ai_training_draft_source (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evaluation_task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (owner_id,task_id),
    KEY ix_ai_training_draft_answer (owner_id,session_id,question_id,answer_id,task_id),
    KEY ix_ai_training_draft_evaluation (owner_id,evaluation_task_id,task_id),
    CONSTRAINT fk_ai_training_draft_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_training_draft_answer FOREIGN KEY (owner_id,session_id,question_id,answer_id)
        REFERENCES training_answer (owner_id,session_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- 评价使用已有 owner 复合主键；服务在同一事务内另核对其会话、题目和回答均与本关系一致。
    CONSTRAINT fk_ai_training_draft_evaluation FOREIGN KEY (owner_id,evaluation_task_id)
        REFERENCES ai_evaluation (owner_id,task_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
