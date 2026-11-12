-- 服务器显式迁移；反馈修订、决定和动作历史只追加，来源身份不随动作合并改变。
CREATE TABLE review_feedback (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_feedback_owner (owner_id,id),
    UNIQUE KEY uk_review_feedback_answer (owner_id,id,session_id,question_id,answer_id),
    KEY ix_review_feedback_answer_page (owner_id,session_id,question_id,answer_id,id),
    CONSTRAINT fk_review_feedback_answer FOREIGN KEY (owner_id,session_id,question_id,answer_id)
        REFERENCES training_answer (owner_id,session_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_review_feedback_revision CHECK (revision BETWEEN 1 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 当前修订指针由同一 owner 命令事务维护；历史正文引用 FeedbackContent 的完整结构。
CREATE TABLE review_feedback_revision (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    feedback_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision INT NOT NULL,
    content_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,feedback_id,revision),
    CONSTRAINT fk_review_feedback_revision_feedback FOREIGN KEY (owner_id,feedback_id)
        REFERENCES review_feedback (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_review_feedback_revision_number CHECK (revision BETWEEN 1 AND 100),
    CONSTRAINT ck_review_feedback_revision_content CHECK (JSON_VALID(content_json)
        AND JSON_TYPE(CAST(content_json AS JSON))='OBJECT' AND OCTET_LENGTH(content_json)<=131072)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE review_feedback_decision (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    feedback_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    feedback_revision INT NOT NULL,
    revision INT NOT NULL,
    choice VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_decision_revision (owner_id,feedback_id,feedback_revision,revision),
    CONSTRAINT fk_review_decision_feedback_revision FOREIGN KEY (owner_id,feedback_id,feedback_revision)
        REFERENCES review_feedback_revision (owner_id,feedback_id,revision) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_review_decision_revision CHECK (revision BETWEEN 1 AND 20),
    CONSTRAINT ck_review_decision_choice CHECK (choice IN ('ACCEPTED','DISPUTED','IGNORED')),
    CONSTRAINT ck_review_decision_reason CHECK (CHAR_LENGTH(reason)<=1000
        AND (choice<>'DISPUTED' OR CHAR_LENGTH(TRIM(reason))>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE review_action (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    title VARCHAR(200) NOT NULL,
    detail TEXT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merged_into CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_action_owner (owner_id,id),
    KEY ix_review_action_status_page (owner_id,status,id),
    CONSTRAINT fk_review_action_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_review_action_merged_into FOREIGN KEY (owner_id,merged_into)
        REFERENCES review_action (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_review_action_revision CHECK (revision BETWEEN 0 AND 1000),
    CONSTRAINT ck_review_action_text CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200 AND CHAR_LENGTH(detail)<=2000),
    CONSTRAINT ck_review_action_status CHECK (
        (status='OPEN' AND merged_into IS NULL)
        OR (status='MERGED' AND merged_into IS NOT NULL AND merged_into<>id))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE review_action_source (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    origin_action_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    feedback_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    feedback_revision INT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    observation_id VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NULL,
    manual_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    author_label VARCHAR(120) NULL,
    observation_json MEDIUMTEXT NOT NULL,
    decision_json TEXT NULL,
    verification TEXT NOT NULL,
    tracking VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tracking_reason VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_source_owner (owner_id,id),
    KEY ix_review_source_action (owner_id,action_id,created_at,id),
    KEY ix_review_source_origin (owner_id,origin_action_id,id),
    CONSTRAINT fk_review_source_action FOREIGN KEY (owner_id,action_id)
        REFERENCES review_action (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_review_source_origin FOREIGN KEY (owner_id,origin_action_id)
        REFERENCES review_action (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_review_source_answer FOREIGN KEY (owner_id,session_id,question_id,answer_id)
        REFERENCES training_answer (owner_id,session_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_review_source_feedback_answer FOREIGN KEY (owner_id,feedback_id,session_id,question_id,answer_id)
        REFERENCES review_feedback (owner_id,id,session_id,question_id,answer_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_review_source_feedback_revision FOREIGN KEY (owner_id,feedback_id,feedback_revision)
        REFERENCES review_feedback_revision (owner_id,feedback_id,revision) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_review_source_ai_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_review_source_identity CHECK (
        (kind='MANUAL' AND feedback_id IS NOT NULL AND feedback_revision IS NOT NULL AND feedback_revision BETWEEN 1 AND 100
            AND task_id IS NULL AND observation_id IS NULL AND manual_kind IS NOT NULL AND author_label IS NOT NULL
            AND ((manual_kind='SELF_CHECK' AND CHAR_LENGTH(author_label)=0)
                OR (manual_kind='EXTERNAL_FEEDBACK' AND CHAR_LENGTH(TRIM(author_label)) BETWEEN 1 AND 120)))
        OR (kind='AI_EVALUATION' AND feedback_id IS NULL AND feedback_revision IS NULL
            AND task_id IS NOT NULL AND observation_id IS NOT NULL AND manual_kind IS NULL AND author_label IS NULL
            AND ((CHAR_LENGTH(observation_id)=2 AND observation_id IN ('f1','f2','f3','f4','f5','f6','f7','f8','f9'))
                OR observation_id IN ('f10','f11','f12')))),
    CONSTRAINT ck_review_source_observation CHECK (JSON_VALID(observation_json)
        AND JSON_TYPE(CAST(observation_json AS JSON))='OBJECT' AND OCTET_LENGTH(observation_json)<=131072),
    CONSTRAINT ck_review_source_decision CHECK (decision_json IS NULL OR (JSON_VALID(decision_json)
        AND JSON_TYPE(CAST(decision_json AS JSON))='OBJECT' AND OCTET_LENGTH(decision_json)<=16384)),
    CONSTRAINT ck_review_source_verification CHECK (CHAR_LENGTH(TRIM(verification)) BETWEEN 1 AND 2000),
    CONSTRAINT ck_review_source_tracking CHECK (tracking IN ('TRACKING','DROPPED') AND CHAR_LENGTH(tracking_reason)<=1000
        AND (tracking<>'DROPPED' OR CHAR_LENGTH(TRIM(tracking_reason))>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 每次修订记录完整 Action（含全部来源）；合并两端各自追加，原来源身份与当时验证条件可回看。
CREATE TABLE review_action_history (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    change_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,action_id,revision),
    CONSTRAINT fk_review_history_action FOREIGN KEY (owner_id,action_id)
        REFERENCES review_action (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_review_history_revision CHECK (revision BETWEEN 0 AND 1000),
    CONSTRAINT ck_review_history_change CHECK (change_kind IN ('CREATE','EDIT','SOURCE','MERGE_IN','MERGE_OUT')),
    CONSTRAINT ck_review_history_initial CHECK ((revision=0 AND change_kind='CREATE') OR (revision>0 AND change_kind<>'CREATE')),
    CONSTRAINT ck_review_history_snapshot CHECK (JSON_VALID(snapshot_json)
        AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json)<=4194304)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
