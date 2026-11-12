-- 自检／外部反馈只作为用户所选原文依据追加；正式边可删除，历史身份与反馈修订不改写。
CREATE TABLE knowledge_relation_feedback_evidence (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    relation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    relation_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    source_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    feedback_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    feedback_revision INT NOT NULL,
    source_count INT NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision_revision INT NULL,
    decision_choice VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,id),
    KEY ix_relation_feedback_evidence_relation (owner_id,relation_id,id),
    KEY ix_relation_feedback_evidence_revision (owner_id,feedback_id,feedback_revision,id),
    KEY ix_relation_feedback_evidence_answer (owner_id,feedback_id,session_id,question_id,answer_id),
    KEY ix_relation_feedback_evidence_decision (owner_id,feedback_id,feedback_revision,decision_revision),
    CONSTRAINT fk_relation_feedback_evidence_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_relation_feedback_evidence_answer FOREIGN KEY (owner_id,feedback_id,session_id,question_id,answer_id)
        REFERENCES review_feedback (owner_id,id,session_id,question_id,answer_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_relation_feedback_evidence_revision FOREIGN KEY (owner_id,feedback_id,feedback_revision)
        REFERENCES review_feedback_revision (owner_id,feedback_id,revision) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_relation_feedback_evidence_decision FOREIGN KEY (owner_id,feedback_id,feedback_revision,decision_revision)
        REFERENCES review_feedback_decision (owner_id,feedback_id,feedback_revision,revision) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_relation_feedback_evidence_type CHECK (relation_type IN ('PRECEDES','PART_OF','APPLIED_IN','RELATED','CONTRASTS')),
    CONSTRAINT ck_relation_feedback_evidence_endpoints CHECK (source_id<>target_id
        AND (relation_type NOT IN ('RELATED','CONTRASTS') OR source_id<target_id)),
    CONSTRAINT ck_relation_feedback_evidence_reason CHECK (CHAR_LENGTH(reason) BETWEEN 1 AND 1000),
    CONSTRAINT ck_relation_feedback_evidence_source CHECK (source_kind IN ('SELF_CHECK','EXTERNAL_FEEDBACK')
        AND feedback_revision BETWEEN 1 AND 100 AND source_count BETWEEN 1 AND 8),
    CONSTRAINT ck_relation_feedback_evidence_decision CHECK (
        (decision_revision IS NULL AND decision_choice IS NULL)
        OR (decision_revision IS NOT NULL AND decision_revision BETWEEN 1 AND 20
            AND decision_choice IS NOT NULL AND decision_choice IN ('ACCEPTED','DISPUTED','IGNORED'))),
    CONSTRAINT ck_relation_feedback_evidence_snapshot CHECK (JSON_VALID(snapshot_json)
        AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json)<=1048576)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
