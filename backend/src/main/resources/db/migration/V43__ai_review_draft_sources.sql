-- 复盘整理沿用知识候选任务，独立固定反馈修订／决定或回忆版本与源修订，不复制来源正文。
CREATE TABLE ai_review_draft_source (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    feedback_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    feedback_revision INT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    decision_revision INT NULL,
    decision_choice VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    recollection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    recollection_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    recollection_revision BIGINT NULL,
    PRIMARY KEY (owner_id,task_id),
    CONSTRAINT fk_ai_review_draft_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_review_draft_feedback_answer FOREIGN KEY (owner_id,feedback_id,session_id,question_id,answer_id)
        REFERENCES review_feedback (owner_id,id,session_id,question_id,answer_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_review_draft_feedback_revision FOREIGN KEY (owner_id,feedback_id,feedback_revision)
        REFERENCES review_feedback_revision (owner_id,feedback_id,revision) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_review_draft_decision FOREIGN KEY (owner_id,feedback_id,feedback_revision,decision_revision)
        REFERENCES review_feedback_decision (owner_id,feedback_id,feedback_revision,revision) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_review_draft_recollection FOREIGN KEY (owner_id,recollection_id,recollection_version_id)
        REFERENCES review_recollection_version (owner_id,recollection_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_review_draft_identity CHECK (
        (kind IN ('SELF_CHECK','EXTERNAL_FEEDBACK') AND feedback_id IS NOT NULL
            AND feedback_revision IS NOT NULL AND feedback_revision BETWEEN 1 AND 100
            AND session_id IS NOT NULL AND question_id IS NOT NULL AND answer_id IS NOT NULL
            AND recollection_id IS NULL AND recollection_version_id IS NULL AND recollection_revision IS NULL
            AND ((decision_revision IS NULL AND decision_choice IS NULL)
                OR (decision_revision IS NOT NULL AND decision_revision BETWEEN 1 AND 20
                    AND decision_choice IS NOT NULL AND decision_choice IN ('ACCEPTED','DISPUTED','IGNORED'))))
        OR (kind='RECOLLECTION' AND feedback_id IS NULL AND feedback_revision IS NULL
            AND session_id IS NULL AND question_id IS NULL AND answer_id IS NULL
            AND decision_revision IS NULL AND decision_choice IS NULL
            AND recollection_id IS NOT NULL AND recollection_version_id IS NOT NULL
            AND recollection_revision IS NOT NULL AND recollection_revision BETWEEN 0 AND 1000))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
