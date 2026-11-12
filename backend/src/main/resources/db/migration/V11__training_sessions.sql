-- 服务器发布时显式执行；训练条件、题干和正式答案固定为快照，正式答案与事件只追加。
CREATE TABLE training_session (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(200) NOT NULL,
    mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    material_access BOOLEAN NOT NULL DEFAULT FALSE,
    assistance_event_sequence INT NOT NULL DEFAULT 0,
    event_count INT NOT NULL DEFAULT 0,
    config_json MEDIUMTEXT NOT NULL,
    context_json MEDIUMTEXT NOT NULL,
    question_count INT NOT NULL,
    observed_millis BIGINT NOT NULL DEFAULT 0,
    unobserved_millis BIGINT NOT NULL DEFAULT 0,
    paused_millis BIGINT NOT NULL DEFAULT 0,
    observation_at DATETIME(6) NULL,
    pause_started_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    ended_at DATETIME(6) NULL,
    end_checklist_json TEXT NULL,
    active_owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN status IN ('RUNNING','PAUSED') THEN owner_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_training_session_owner_id (owner_id,id),
    UNIQUE KEY uk_training_active_owner (active_owner_id),
    KEY ix_training_session_owner_status_id (owner_id,status,id),
    CONSTRAINT fk_training_session_owner FOREIGN KEY (owner_id) REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_training_session_mode CHECK (mode IN ('LEARNING','FOCUSED','MOCK')),
    CONSTRAINT ck_training_session_status CHECK (status IN ('DRAFT','RUNNING','PAUSED','ENDED')),
    CONSTRAINT ck_training_session_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_training_session_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_training_session_access CHECK (material_access IN (0,1)),
    CONSTRAINT ck_training_session_events CHECK (event_count BETWEEN 0 AND 2000 AND assistance_event_sequence BETWEEN 0 AND event_count),
    CONSTRAINT ck_training_session_questions CHECK (question_count BETWEEN 1 AND 20),
    CONSTRAINT ck_training_session_time CHECK (observed_millis >= 0 AND unobserved_millis >= 0 AND paused_millis >= 0),
    CONSTRAINT ck_training_session_config CHECK (JSON_VALID(config_json) AND JSON_TYPE(CAST(config_json AS JSON))='OBJECT'),
    CONSTRAINT ck_training_session_context CHECK (JSON_VALID(context_json) AND JSON_TYPE(CAST(context_json AS JSON))='ARRAY' AND OCTET_LENGTH(context_json) <= 131072),
    CONSTRAINT ck_training_session_end_json CHECK (end_checklist_json IS NULL OR (JSON_VALID(end_checklist_json) AND JSON_TYPE(CAST(end_checklist_json AS JSON))='OBJECT')),
    CONSTRAINT ck_training_session_clock CHECK (
        (status='DRAFT' AND started_at IS NULL AND ended_at IS NULL AND observation_at IS NULL AND pause_started_at IS NULL)
        OR (status='RUNNING' AND started_at IS NOT NULL AND ended_at IS NULL AND observation_at IS NOT NULL AND pause_started_at IS NULL)
        OR (status='PAUSED' AND started_at IS NOT NULL AND ended_at IS NULL AND observation_at IS NULL AND pause_started_at IS NOT NULL)
        OR (status='ENDED' AND ended_at IS NOT NULL AND observation_at IS NULL AND pause_started_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE training_question (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stage VARCHAR(100) NOT NULL,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    snapshot_json MEDIUMTEXT NOT NULL,
    draft_json MEDIUMTEXT NULL,
    draft_saved_at DATETIME(6) NULL,
    latest_answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    answer_count INT NOT NULL DEFAULT 0,
    exposure_json TEXT NOT NULL,
    position_index INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_training_question_owner_session (owner_id,session_id,id),
    UNIQUE KEY uk_training_question_position (session_id,position_index),
    UNIQUE KEY uk_training_question_source (session_id,source_question_id),
    CONSTRAINT fk_training_question_session FOREIGN KEY (owner_id,session_id) REFERENCES training_session (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_training_question_source FOREIGN KEY (owner_id,source_question_id,source_version_id) REFERENCES question_version (owner_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_training_question_status CHECK (status IN ('PENDING','ANSWERED','SKIPPED')),
    CONSTRAINT ck_training_question_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_training_question_position CHECK (position_index BETWEEN 0 AND 19),
    CONSTRAINT ck_training_question_answers CHECK (answer_count BETWEEN 0 AND 100 AND ((answer_count=0 AND latest_answer_id IS NULL) OR (answer_count>0 AND latest_answer_id IS NOT NULL))),
    CONSTRAINT ck_training_question_answered CHECK (status<>'ANSWERED' OR answer_count>0),
    CONSTRAINT ck_training_question_snapshot CHECK (JSON_VALID(snapshot_json) AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT'),
    CONSTRAINT ck_training_question_draft CHECK ((draft_json IS NULL AND draft_saved_at IS NULL) OR (draft_json IS NOT NULL AND draft_saved_at IS NOT NULL AND JSON_VALID(draft_json) AND JSON_TYPE(CAST(draft_json AS JSON))='OBJECT' AND OCTET_LENGTH(draft_json) <= 196608)),
    CONSTRAINT ck_training_question_exposure CHECK (JSON_VALID(exposure_json) AND JSON_TYPE(CAST(exposure_json AS JSON))='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 最新答案指针与首个答案存在插入顺序，服务在同一 owner 事务中维护该指针。
CREATE TABLE training_answer (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT NOT NULL,
    content_json MEDIUMTEXT NOT NULL,
    answer_condition VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assistance_event_sequence INT NOT NULL,
    exposure_json TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_training_answer_owner_question (owner_id,session_id,question_id,id),
    UNIQUE KEY uk_training_answer_number (question_id,version_number),
    CONSTRAINT fk_training_answer_question FOREIGN KEY (owner_id,session_id,question_id) REFERENCES training_question (owner_id,session_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_training_answer_number CHECK (version_number BETWEEN 1 AND 100),
    CONSTRAINT ck_training_answer_content CHECK (JSON_VALID(content_json) AND JSON_TYPE(CAST(content_json AS JSON))='OBJECT' AND OCTET_LENGTH(content_json) <= 196608),
    CONSTRAINT ck_training_answer_condition CHECK (answer_condition IN ('LEARNING','BEFORE_ASSISTANCE','ASSISTED')),
    CONSTRAINT ck_training_answer_assistance CHECK (assistance_event_sequence BETWEEN 0 AND 2000 AND (answer_condition<>'ASSISTED' OR assistance_event_sequence>0)),
    CONSTRAINT ck_training_answer_exposure CHECK (JSON_VALID(exposure_json) AND JSON_TYPE(CAST(exposure_json AS JSON))='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE training_event (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_sequence INT NOT NULL,
    event_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    detail VARCHAR(1000) NOT NULL,
    duration_seconds BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_training_event_sequence (session_id,event_sequence),
    KEY ix_training_event_owner_session (owner_id,session_id,event_sequence),
    CONSTRAINT fk_training_event_session FOREIGN KEY (owner_id,session_id) REFERENCES training_session (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_training_event_question FOREIGN KEY (owner_id,session_id,question_id) REFERENCES training_question (owner_id,session_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_training_event_sequence CHECK (event_sequence BETWEEN 1 AND 2000),
    CONSTRAINT ck_training_event_kind CHECK (event_kind IN ('CREATED','STARTED','PAUSED','RESUMED','ENDED','MATERIAL_ACCESS','DRAFT_SAVED','ANSWER_SUBMITTED','SKIPPED','UNSKIPPED','UNOBSERVED_INTERVAL','REFERENCE_VIEW','BOARD_IMPORT')),
    CONSTRAINT ck_training_event_duration CHECK (duration_seconds IS NULL OR duration_seconds>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 只记录已执行的显式导入；答案来源必须匹配同一 owner、会话及确切白板修订。
CREATE TABLE training_board_import (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    board_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    board_revision BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_training_board_import_owner_session (owner_id,session_id,id),
    CONSTRAINT fk_training_board_import_session FOREIGN KEY (owner_id,session_id) REFERENCES training_session (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_training_board_import_revision CHECK (board_revision BETWEEN 0 AND 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
