-- 跨域问答固定有序来源；正文和必要限定只保存在任务清单，绑定表支持身份核对与反向影响查询。
ALTER TABLE ai_task
    DROP CHECK ck_ai_task_purpose_v47,
    ADD CONSTRAINT ck_ai_task_purpose_v57 CHECK (purpose IN (
        'KNOWLEDGE_DRAFT','QUESTION_DRAFT','FOLLOW_UP','ANSWER_EVALUATION','MATERIAL_QA','EXPRESSION_DRAFT',
        'PREPARATION_PLAN','RELATION_DRAFT','ANSWER_COMPARISON','MEMORY_CARD_DRAFT','JD_REQUIREMENT_DRAFT','FACT_DRAFT',
        'ANSWER_REWRITE','SOURCE_QA'));

ALTER TABLE ai_scope_authorization
    DROP CHECK ck_ai_scope_authorization_purpose,
    ADD CONSTRAINT ck_ai_scope_authorization_purpose_v57 CHECK (purpose IN (
        'KNOWLEDGE_DRAFT','QUESTION_DRAFT','FOLLOW_UP','ANSWER_EVALUATION','MATERIAL_QA','EXPRESSION_DRAFT',
        'PREPARATION_PLAN','RELATION_DRAFT','ANSWER_COMPARISON','MEMORY_CARD_DRAFT','JD_REQUIREMENT_DRAFT','FACT_DRAFT',
        'ANSWER_REWRITE','SOURCE_QA'));

CREATE TABLE ai_source_qa_source (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    domain VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    knowledge_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    preparation_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    preparation_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    excerpt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    bank_question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    bank_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    training_question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    manual_feedback_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    manual_feedback_revision INT NULL,
    recollection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    recollection_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    evaluation_task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    observation_id VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL,
    report_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    decision_revision INT NULL,
    decision_choice VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    fragment_count INT NOT NULL,
    snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (owner_id,task_id,ordinal),
    KEY ix_source_qa_knowledge (owner_id,knowledge_item_id,knowledge_version_id,task_id),
    KEY ix_source_qa_preparation (owner_id,preparation_item_id,preparation_version_id,task_id),
    KEY ix_source_qa_excerpt (owner_id,excerpt_id,task_id),
    KEY ix_source_qa_question (owner_id,bank_question_id,bank_version_id,task_id),
    KEY ix_source_qa_answer (owner_id,session_id,training_question_id,answer_id,task_id),
    KEY ix_source_qa_feedback (owner_id,manual_feedback_id,manual_feedback_revision,task_id),
    KEY ix_source_qa_recollection (owner_id,recollection_id,recollection_version_id,task_id),
    KEY ix_source_qa_ai (owner_id,evaluation_task_id,attempt_id,observation_id,task_id),
    CONSTRAINT fk_source_qa_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_source_qa_knowledge FOREIGN KEY (owner_id,knowledge_item_id,knowledge_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_source_qa_preparation FOREIGN KEY (owner_id,preparation_item_id,preparation_version_id)
        REFERENCES preparation_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_source_qa_excerpt FOREIGN KEY (owner_id,excerpt_id)
        REFERENCES material_excerpt (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_source_qa_question FOREIGN KEY (owner_id,bank_question_id,bank_version_id)
        REFERENCES question_version (owner_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_source_qa_answer FOREIGN KEY (owner_id,session_id,training_question_id,answer_id)
        REFERENCES training_answer (owner_id,session_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_source_qa_feedback_answer FOREIGN KEY (owner_id,manual_feedback_id,session_id,training_question_id,answer_id)
        REFERENCES review_feedback (owner_id,id,session_id,question_id,answer_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_source_qa_feedback_revision FOREIGN KEY (owner_id,manual_feedback_id,manual_feedback_revision)
        REFERENCES review_feedback_revision (owner_id,feedback_id,revision) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_source_qa_feedback_decision FOREIGN KEY (owner_id,manual_feedback_id,manual_feedback_revision,decision_revision)
        REFERENCES review_feedback_decision (owner_id,feedback_id,feedback_revision,revision) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_source_qa_recollection FOREIGN KEY (owner_id,recollection_id,recollection_version_id)
        REFERENCES review_recollection_version (owner_id,recollection_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_source_qa_ai_answer FOREIGN KEY (owner_id,evaluation_task_id,session_id,training_question_id,answer_id)
        REFERENCES ai_evaluation (owner_id,task_id,session_id,question_id,answer_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_source_qa_ai_attempt FOREIGN KEY (owner_id,evaluation_task_id,attempt_id)
        REFERENCES ai_attempt (owner_id,task_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_source_qa_ai_decision FOREIGN KEY (owner_id,evaluation_task_id,observation_id,decision_revision)
        REFERENCES ai_feedback_decision (owner_id,task_id,feedback_id,revision) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_source_qa_base CHECK (ordinal BETWEEN 1 AND 5 AND fragment_count BETWEEN 1 AND 8
        AND domain IN ('KNOWLEDGE','PREPARATION','MATERIAL_EXCERPT','QUESTION','TRAINING_ANSWER','REVIEW_FEEDBACK','RECOLLECTION','AI_FEEDBACK')
        AND REGEXP_LIKE(snapshot_hash,'^[0-9a-f]{64}$','c')),
    CONSTRAINT ck_source_qa_knowledge CHECK (
        (domain='KNOWLEDGE' AND knowledge_item_id IS NOT NULL AND knowledge_version_id IS NOT NULL)
        OR (domain<>'KNOWLEDGE' AND knowledge_item_id IS NULL AND knowledge_version_id IS NULL)),
    CONSTRAINT ck_source_qa_preparation CHECK (
        (domain='PREPARATION' AND preparation_item_id IS NOT NULL AND preparation_version_id IS NOT NULL)
        OR (domain<>'PREPARATION' AND preparation_item_id IS NULL AND preparation_version_id IS NULL)),
    CONSTRAINT ck_source_qa_excerpt CHECK (
        (domain='MATERIAL_EXCERPT' AND excerpt_id IS NOT NULL) OR (domain<>'MATERIAL_EXCERPT' AND excerpt_id IS NULL)),
    CONSTRAINT ck_source_qa_question CHECK (
        (domain='QUESTION' AND bank_question_id IS NOT NULL AND bank_version_id IS NOT NULL)
        OR (domain<>'QUESTION' AND bank_question_id IS NULL AND bank_version_id IS NULL)),
    CONSTRAINT ck_source_qa_training CHECK (
        (domain IN ('TRAINING_ANSWER','REVIEW_FEEDBACK','AI_FEEDBACK')
            AND session_id IS NOT NULL AND training_question_id IS NOT NULL AND answer_id IS NOT NULL)
        OR (domain NOT IN ('TRAINING_ANSWER','REVIEW_FEEDBACK','AI_FEEDBACK')
            AND session_id IS NULL AND training_question_id IS NULL AND answer_id IS NULL)),
    CONSTRAINT ck_source_qa_feedback CHECK (
        (domain='REVIEW_FEEDBACK' AND manual_feedback_id IS NOT NULL
            AND manual_feedback_revision IS NOT NULL AND manual_feedback_revision BETWEEN 1 AND 100)
        OR (domain<>'REVIEW_FEEDBACK' AND manual_feedback_id IS NULL AND manual_feedback_revision IS NULL)),
    CONSTRAINT ck_source_qa_recollection CHECK (
        (domain='RECOLLECTION' AND recollection_id IS NOT NULL AND recollection_version_id IS NOT NULL)
        OR (domain<>'RECOLLECTION' AND recollection_id IS NULL AND recollection_version_id IS NULL)),
    CONSTRAINT ck_source_qa_ai CHECK (
        (domain='AI_FEEDBACK' AND evaluation_task_id IS NOT NULL AND attempt_id IS NOT NULL
            AND observation_id IS NOT NULL AND observation_id IN ('f1','f2','f3','f4','f5','f6','f7','f8','f9','f10','f11','f12')
            AND report_hash IS NOT NULL AND REGEXP_LIKE(report_hash,'^[0-9a-f]{64}$','c'))
        OR (domain<>'AI_FEEDBACK' AND evaluation_task_id IS NULL AND attempt_id IS NULL AND observation_id IS NULL AND report_hash IS NULL)),
    CONSTRAINT ck_source_qa_decision CHECK (
        (decision_revision IS NULL AND decision_choice IS NULL)
        OR (domain IN ('REVIEW_FEEDBACK','AI_FEEDBACK') AND decision_revision IS NOT NULL AND decision_revision BETWEEN 1 AND 20
            AND decision_choice IS NOT NULL AND decision_choice IN ('ACCEPTED','DISPUTED','IGNORED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
