-- 服务器显式升级；保留已开始训练的固定快照，生命周期代次与普通 revision 分开。
ALTER TABLE training_session
    ADD COLUMN generation BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_training_generation CHECK (generation BETWEEN 0 AND 9007199254740991),
    DROP CHECK ck_training_session_questions,
    ADD CONSTRAINT ck_training_session_questions_v15 CHECK (question_count BETWEEN 1 AND 60);

ALTER TABLE ai_task
    DROP CHECK ck_ai_task_purpose_v14,
    ADD CONSTRAINT ck_ai_task_purpose_v15 CHECK (purpose IN ('KNOWLEDGE_DRAFT','QUESTION_DRAFT','FOLLOW_UP'));

ALTER TABLE training_question
    DROP FOREIGN KEY fk_training_question_source,
    MODIFY COLUMN source_question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    MODIFY COLUMN source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT fk_training_question_source FOREIGN KEY (owner_id,source_question_id,source_version_id)
        REFERENCES question_version (owner_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD COLUMN question_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MAIN',
    ADD COLUMN root_question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN parent_question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN parent_answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN ai_task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_training_question_ai_task (owner_id,ai_task_id),
    ADD CONSTRAINT fk_training_follow_up_root FOREIGN KEY (owner_id,session_id,root_question_id)
        REFERENCES training_question (owner_id,session_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_training_follow_up_parent_answer FOREIGN KEY (owner_id,session_id,parent_question_id,parent_answer_id)
        REFERENCES training_answer (owner_id,session_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_training_follow_up_task FOREIGN KEY (owner_id,ai_task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    DROP CHECK ck_training_question_position,
    ADD CONSTRAINT ck_training_question_position_v15 CHECK (position_index BETWEEN 0 AND 59),
    ADD CONSTRAINT ck_training_question_origin CHECK (
        (question_kind='MAIN' AND source_question_id IS NOT NULL AND source_version_id IS NOT NULL
            AND root_question_id IS NULL AND parent_question_id IS NULL AND parent_answer_id IS NULL AND ai_task_id IS NULL)
        OR (question_kind='FOLLOW_UP' AND source_question_id IS NULL AND source_version_id IS NULL
            AND root_question_id IS NOT NULL AND parent_question_id IS NOT NULL AND parent_answer_id IS NOT NULL
            AND ai_task_id IS NOT NULL AND id<>root_question_id AND id<>parent_question_id));

ALTER TABLE training_event
    DROP CHECK ck_training_event_kind,
    ADD CONSTRAINT ck_training_event_kind_v15 CHECK (event_kind IN ('CREATED','STARTED','PAUSED','RESUMED','ENDED',
        'MATERIAL_ACCESS','DRAFT_SAVED','ANSWER_SUBMITTED','SKIPPED','UNSKIPPED','UNOBSERVED_INTERVAL',
        'REFERENCE_VIEW','BOARD_IMPORT','FOLLOW_UP_ADDED'));

CREATE TABLE ai_follow_up (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    root_question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation BIGINT NOT NULL,
    generated_question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,task_id),
    UNIQUE KEY uk_ai_follow_up_generated (owner_id,session_id,generated_question_id),
    KEY ix_ai_follow_up_session_task (owner_id,session_id,task_id),
    KEY ix_ai_follow_up_root (owner_id,session_id,root_question_id),
    CONSTRAINT fk_ai_follow_up_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_follow_up_answer FOREIGN KEY (owner_id,session_id,question_id,answer_id)
        REFERENCES training_answer (owner_id,session_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_follow_up_root FOREIGN KEY (owner_id,session_id,root_question_id)
        REFERENCES training_question (owner_id,session_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_follow_up_generated FOREIGN KEY (owner_id,session_id,generated_question_id)
        REFERENCES training_question (owner_id,session_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_follow_up_generation CHECK (generation BETWEEN 0 AND 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
