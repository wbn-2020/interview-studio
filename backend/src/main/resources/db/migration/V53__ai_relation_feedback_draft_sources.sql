-- 新关系生成任务仅绑定确切评价身份；完整选区与限定保存在任务固定清单中。
CREATE TABLE ai_relation_feedback_draft_source (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evaluation_task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    feedback_id VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision_revision INT NULL,
    decision_choice VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    level VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_count INT NOT NULL,
    PRIMARY KEY (owner_id,task_id),
    KEY ix_relation_feedback_draft_answer (owner_id,evaluation_task_id,session_id,question_id,answer_id),
    KEY ix_relation_feedback_draft_attempt (owner_id,evaluation_task_id,attempt_id),
    KEY ix_relation_feedback_draft_decision (owner_id,evaluation_task_id,feedback_id,decision_revision),
    CONSTRAINT fk_relation_feedback_draft_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_relation_feedback_draft_answer FOREIGN KEY (owner_id,evaluation_task_id,session_id,question_id,answer_id)
        REFERENCES ai_evaluation (owner_id,task_id,session_id,question_id,answer_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_relation_feedback_draft_attempt FOREIGN KEY (owner_id,evaluation_task_id,attempt_id)
        REFERENCES ai_attempt (owner_id,task_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_relation_feedback_draft_decision FOREIGN KEY (owner_id,evaluation_task_id,feedback_id,decision_revision)
        REFERENCES ai_feedback_decision (owner_id,task_id,feedback_id,revision) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_relation_feedback_draft_source CHECK (source_count BETWEEN 1 AND 8
        AND feedback_id IN ('f1','f2','f3','f4','f5','f6','f7','f8','f9','f10','f11','f12')
        AND REGEXP_LIKE(report_hash,'^[0-9a-f]{64}$','c') AND level='AI_UNREVIEWED'),
    CONSTRAINT ck_relation_feedback_draft_decision CHECK (
        (decision_revision IS NULL AND decision_choice IS NULL)
        OR (decision_revision IS NOT NULL AND decision_revision BETWEEN 1 AND 20
            AND decision_choice IS NOT NULL AND decision_choice IN ('ACCEPTED','DISPUTED','IGNORED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
