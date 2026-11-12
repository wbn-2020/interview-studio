-- 生命周期独立于固定摘录正文；历史基线不补造用户动作事件。
CREATE TABLE material_excerpt_lifecycle (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    excerpt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attachment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ai_use VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,excerpt_id),
    KEY ix_material_lifecycle_selection (owner_id,attachment_id,status,ai_use,excerpt_id),
    CONSTRAINT fk_material_lifecycle_excerpt FOREIGN KEY (owner_id,excerpt_id)
        REFERENCES material_excerpt (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_material_lifecycle_attachment FOREIGN KEY (owner_id,attachment_id)
        REFERENCES material_attachment (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_material_lifecycle_status CHECK (status IN ('ACTIVE','ARCHIVED')),
    CONSTRAINT ck_material_lifecycle_ai_use CHECK (ai_use IN ('ALLOWED','REVOKED')),
    CONSTRAINT ck_material_lifecycle_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_material_lifecycle_time CHECK (updated_at>=created_at),
    CONSTRAINT ck_material_lifecycle_baseline CHECK (revision<>0 OR
        (status='ACTIVE' AND ai_use='ALLOWED' AND updated_at=created_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO material_excerpt_lifecycle(owner_id,excerpt_id,attachment_id,status,ai_use,revision,created_at,updated_at)
SELECT owner_id,id,attachment_id,'ACTIVE','ALLOWED',0,created_at,created_at FROM material_excerpt;

CREATE TABLE material_excerpt_lifecycle_event (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    excerpt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_ai_use VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_revision BIGINT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ai_use VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,id),
    UNIQUE KEY uk_material_lifecycle_event_revision (owner_id,excerpt_id,revision),
    CONSTRAINT fk_material_lifecycle_event_excerpt FOREIGN KEY (owner_id,excerpt_id)
        REFERENCES material_excerpt_lifecycle (owner_id,excerpt_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_material_lifecycle_event_revision CHECK (revision BETWEEN 1 AND 9007199254740991 AND previous_revision=revision-1),
    CONSTRAINT ck_material_lifecycle_event_status CHECK (status IN ('ACTIVE','ARCHIVED') AND previous_status IN ('ACTIVE','ARCHIVED')
        AND ai_use IN ('ALLOWED','REVOKED') AND previous_ai_use IN ('ALLOWED','REVOKED')),
    CONSTRAINT ck_material_lifecycle_event_reason CHECK (CHAR_LENGTH(reason) BETWEEN 1 AND 1000),
    CONSTRAINT ck_material_lifecycle_event_transition CHECK (
        (action='ARCHIVE' AND previous_status='ACTIVE' AND status='ARCHIVED' AND ai_use=previous_ai_use)
        OR (action='RESTORE' AND previous_status='ARCHIVED' AND status='ACTIVE' AND ai_use=previous_ai_use)
        OR (action='REVOKE_AI' AND previous_ai_use='ALLOWED' AND ai_use='REVOKED' AND status=previous_status)
        OR (action='ALLOW_AI' AND previous_ai_use='REVOKED' AND ai_use='ALLOWED' AND status=previous_status))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
