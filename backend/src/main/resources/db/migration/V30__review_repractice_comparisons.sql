-- 服务器显式迁移；复练固定原答、新题与所选来源，比较和判断均只追加。
CREATE TABLE review_repractice (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    baseline_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    baseline_question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    baseline_answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_repractice_owner (owner_id,id),
    UNIQUE KEY uk_repractice_session (owner_id,session_id),
    UNIQUE KEY uk_repractice_question (owner_id,id,session_id,question_id),
    CONSTRAINT fk_repractice_baseline FOREIGN KEY (owner_id,baseline_session_id,baseline_question_id,baseline_answer_id)
        REFERENCES training_answer (owner_id,session_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_repractice_question FOREIGN KEY (owner_id,session_id,question_id)
        REFERENCES training_question (owner_id,session_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_repractice_mode CHECK (question_mode IN ('SAME','VARIANT')),
    CONSTRAINT ck_repractice_new_session CHECK (session_id<>baseline_session_id AND question_id<>baseline_question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE review_repractice_source (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    repractice_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_revision BIGINT NOT NULL,
    position_index INT NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    PRIMARY KEY (owner_id,repractice_id,source_id),
    UNIQUE KEY uk_repractice_source_position (owner_id,repractice_id,position_index),
    CONSTRAINT fk_repractice_source_repractice FOREIGN KEY (owner_id,repractice_id)
        REFERENCES review_repractice (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_repractice_source_identity FOREIGN KEY (owner_id,source_id)
        REFERENCES review_action_source (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_repractice_source_action_history FOREIGN KEY (owner_id,action_id,action_revision)
        REFERENCES review_action_history (owner_id,action_id,revision) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_repractice_source_revision CHECK (action_revision BETWEEN 0 AND 1000),
    CONSTRAINT ck_repractice_source_position CHECK (position_index BETWEEN 0 AND 9),
    CONSTRAINT ck_repractice_source_snapshot CHECK (JSON_VALID(snapshot_json)
        AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json)<=196608)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE review_comparison (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    repractice_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_number INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_comparison_owner (owner_id,id),
    UNIQUE KEY uk_review_comparison_answer (owner_id,repractice_id,answer_id),
    UNIQUE KEY uk_review_comparison_identity (owner_id,id,repractice_id,answer_id),
    KEY ix_review_comparison_order (owner_id,repractice_id,answer_number,id),
    CONSTRAINT fk_review_comparison_repractice FOREIGN KEY (owner_id,repractice_id,session_id,question_id)
        REFERENCES review_repractice (owner_id,id,session_id,question_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_review_comparison_answer FOREIGN KEY (owner_id,session_id,question_id,answer_id)
        REFERENCES training_answer (owner_id,session_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_review_comparison_number CHECK (answer_number BETWEEN 1 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- repractice_id 从确切 comparison 派生，使判断只能引用该次复练已固定的来源。
CREATE TABLE review_source_judgment (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision INT NOT NULL,
    comparison_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    repractice_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    verification TEXT NOT NULL,
    outcome VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason TEXT NOT NULL,
    citations_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_judgment_revision (owner_id,source_id,revision),
    CONSTRAINT fk_review_judgment_comparison FOREIGN KEY (owner_id,comparison_id,repractice_id,answer_id)
        REFERENCES review_comparison (owner_id,id,repractice_id,answer_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_review_judgment_source FOREIGN KEY (owner_id,repractice_id,source_id)
        REFERENCES review_repractice_source (owner_id,repractice_id,source_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_review_judgment_revision CHECK (revision BETWEEN 1 AND 1000),
    CONSTRAINT ck_review_judgment_text CHECK (CHAR_LENGTH(TRIM(verification))>0 AND CHAR_LENGTH(verification)<=2000
        AND CHAR_LENGTH(TRIM(reason))>0 AND CHAR_LENGTH(reason)<=2000),
    CONSTRAINT ck_review_judgment_outcome CHECK (outcome IN ('HANDLED','PARTIAL','UNHANDLED','UNCERTAIN')),
    CONSTRAINT ck_review_judgment_citations CHECK (JSON_VALID(citations_json)
        AND JSON_TYPE(CAST(citations_json AS JSON))='ARRAY' AND JSON_LENGTH(citations_json)<=6
        AND OCTET_LENGTH(citations_json)<=32768
        AND (outcome NOT IN ('HANDLED','PARTIAL') OR JSON_LENGTH(citations_json)>=1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
