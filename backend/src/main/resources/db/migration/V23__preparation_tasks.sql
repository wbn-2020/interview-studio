-- 服务器显式迁移；普通准备任务与面试事件、回忆卡、复练动作分别保持身份和完成语义。
CREATE TABLE preparation_task (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    title VARCHAR(200) NOT NULL,
    detail TEXT NOT NULL,
    minutes INT NOT NULL,
    planned_date DATE NULL,
    zone_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_version_number INT NULL,
    target_title VARCHAR(200) NULL,
    source_ai_task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_step INT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_preparation_task_owner (owner_id,id),
    UNIQUE KEY uk_preparation_task_ai_step (owner_id,source_ai_task_id,source_step),
    KEY ix_preparation_task_status (owner_id,status,id),
    KEY ix_preparation_task_date (owner_id,planned_date,id),
    KEY ix_preparation_task_target (owner_id,target_item_id,id),
    CONSTRAINT fk_preparation_task_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_preparation_task_target FOREIGN KEY (owner_id,target_item_id,target_version_id)
        REFERENCES preparation_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_preparation_task_ai FOREIGN KEY (owner_id,source_ai_task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_preparation_task_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_preparation_task_text CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200 AND CHAR_LENGTH(detail)<=1000),
    CONSTRAINT ck_preparation_task_minutes CHECK (minutes BETWEEN 1 AND 1440),
    CONSTRAINT ck_preparation_task_date CHECK (planned_date IS NULL OR planned_date BETWEEN '1000-01-01' AND '9999-12-31'),
    CONSTRAINT ck_preparation_task_zone CHECK (CHAR_LENGTH(zone_id) BETWEEN 1 AND 100),
    CONSTRAINT ck_preparation_task_target CHECK (
        (target_item_id IS NULL AND target_version_id IS NULL AND target_version_number IS NULL AND target_title IS NULL)
        OR (target_item_id IS NOT NULL AND target_version_id IS NOT NULL AND target_version_number IS NOT NULL
            AND target_version_number>0 AND target_title IS NOT NULL AND CHAR_LENGTH(TRIM(target_title)) BETWEEN 1 AND 200)),
    CONSTRAINT ck_preparation_task_ai CHECK (
        (source_ai_task_id IS NULL AND source_step IS NULL)
        OR (source_ai_task_id IS NOT NULL AND source_step IS NOT NULL AND source_step BETWEEN 1 AND 20 AND target_item_id IS NOT NULL)),
    CONSTRAINT ck_preparation_task_status CHECK (status IN ('TODO','IN_PROGRESS','PAUSED','DONE','CANCELLED')),
    CONSTRAINT ck_preparation_task_completion CHECK (
        (status='DONE' AND completed_at IS NOT NULL) OR (status<>'DONE' AND completed_at IS NULL)),
    CONSTRAINT ck_preparation_task_started CHECK (
        (status='TODO' AND started_at IS NULL) OR (status='IN_PROGRESS' AND started_at IS NOT NULL)
        OR status IN ('PAUSED','DONE','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 快照追加保存；后续编辑不能改写当时的目标版本、来源步骤或完成记录。
CREATE TABLE preparation_task_history (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,task_id,revision),
    CONSTRAINT fk_preparation_task_history_task FOREIGN KEY (owner_id,task_id)
        REFERENCES preparation_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_preparation_task_history_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_preparation_task_history_action CHECK (action IN ('CREATE','EDIT','STATUS')),
    CONSTRAINT ck_preparation_task_history_initial CHECK ((revision=0 AND action='CREATE') OR (revision>0 AND action<>'CREATE')),
    CONSTRAINT ck_preparation_task_history_json CHECK (JSON_VALID(snapshot_json)
        AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json)<=65536)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
