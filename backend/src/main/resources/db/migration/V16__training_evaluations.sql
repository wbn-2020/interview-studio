-- 显式服务器升级；评价独立保存，不改写已提交答案与历史反馈。
ALTER TABLE ai_task
    DROP CHECK ck_ai_task_purpose_v15,
    ADD CONSTRAINT ck_ai_task_purpose_v16 CHECK (purpose IN ('KNOWLEDGE_DRAFT','QUESTION_DRAFT','FOLLOW_UP','ANSWER_EVALUATION'));

ALTER TABLE question_exposure
    DROP CHECK ck_question_exposure_kind,
    ADD CONSTRAINT ck_question_exposure_kind_v16 CHECK (exposure_kind IN ('AUTHORED','REFERENCE_VIEW','EVALUATION_ACCESS'));

CREATE TABLE ai_evaluation_access (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    early_feedback BOOLEAN NOT NULL,
    granted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,session_id,question_id,answer_id),
    CONSTRAINT fk_evaluation_access_answer FOREIGN KEY (owner_id,session_id,question_id,answer_id)
        REFERENCES training_answer (owner_id,session_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_evaluation_access_early CHECK (early_feedback IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_evaluation (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,task_id),
    KEY ix_evaluation_session (owner_id,session_id,task_id),
    KEY ix_evaluation_answer (owner_id,answer_id),
    CONSTRAINT fk_evaluation_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_evaluation_access FOREIGN KEY (owner_id,session_id,question_id,answer_id)
        REFERENCES ai_evaluation_access (owner_id,session_id,question_id,answer_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_feedback_decision (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    feedback_id VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision INT NOT NULL,
    choice VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_feedback_revision (owner_id,task_id,feedback_id,revision),
    CONSTRAINT fk_feedback_evaluation FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_evaluation (owner_id,task_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_feedback_id CHECK (feedback_id IN ('f1','f2','f3','f4','f5','f6','f7','f8','f9','f10','f11','f12')),
    CONSTRAINT ck_feedback_revision CHECK (revision BETWEEN 1 AND 20),
    CONSTRAINT ck_feedback_choice CHECK (choice IN ('ACCEPTED','DISPUTED','IGNORED')),
    CONSTRAINT ck_feedback_reason CHECK (choice <> 'DISPUTED' OR CHAR_LENGTH(TRIM(reason)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
