-- 仅供服务器显式迁移；准备关联不改变原事件修订、来源目标或实际开始证据。
CREATE TABLE interview_preparation_link (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    source_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_interview_preparation_identity (owner_id,event_id,id),
    UNIQUE KEY uk_interview_preparation_source (owner_id,event_id,source_kind,source_id),
    KEY ix_interview_preparation_directory (owner_id,event_id,status,id),
    KEY ix_interview_preparation_reverse (owner_id,source_kind,source_id,status,event_id,id),
    CONSTRAINT fk_interview_preparation_event FOREIGN KEY (owner_id,event_id)
      REFERENCES interview_event (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_interview_preparation_revision CHECK (revision BETWEEN 0 AND 1000),
    CONSTRAINT ck_interview_preparation_source CHECK (
      (source_kind IN ('PREPARATION_TASK','REVIEW_ACTION') AND source_version_id IS NULL)
      OR (source_kind='COLLECTION' AND source_version_id IS NOT NULL)),
    CONSTRAINT ck_interview_preparation_status CHECK (status IN ('ACTIVE','REMOVED')),
    CONSTRAINT ck_interview_preparation_clock CHECK (updated_at >= created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE interview_preparation_link_history (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    link_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    event_revision BIGINT NOT NULL,
    change_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    source_stamp_json TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,event_id,link_id,revision),
    CONSTRAINT fk_interview_preparation_history_link FOREIGN KEY (owner_id,event_id,link_id)
      REFERENCES interview_preparation_link (owner_id,event_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_interview_preparation_history_event FOREIGN KEY (owner_id,event_id,event_revision)
      REFERENCES interview_event_history (owner_id,event_id,revision) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_interview_preparation_history_revision CHECK (revision BETWEEN 0 AND 1000),
    CONSTRAINT ck_interview_preparation_history_event_revision CHECK (event_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_interview_preparation_history_kind CHECK (change_kind IN ('CREATED','REMOVED','RESTORED','VERSION_CHANGED')),
    CONSTRAINT ck_interview_preparation_history_initial CHECK (
      (revision=0 AND change_kind='CREATED') OR (revision>0 AND change_kind<>'CREATED')),
    CONSTRAINT ck_interview_preparation_history_source CHECK (
      (change_kind='REMOVED' AND source_stamp_json IS NULL)
      OR (change_kind<>'REMOVED' AND source_stamp_json IS NOT NULL
        AND JSON_VALID(source_stamp_json) AND JSON_TYPE(CAST(source_stamp_json AS JSON))='OBJECT')),
    CONSTRAINT ck_interview_preparation_history_snapshot CHECK (JSON_VALID(snapshot_json)
      AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json)<=32768)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 独立改日仍保存 PreparationTask 原快照，仅追加明确动作；不重写 V23 或任何旧历史。
ALTER TABLE preparation_task_history
    DROP CHECK ck_preparation_task_history_action,
    ADD CONSTRAINT ck_preparation_task_history_action CHECK (action IN ('CREATE','EDIT','STATUS','RESCHEDULE'));
