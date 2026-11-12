-- 持续授权只保存范围摘要与公开配置元数据；正文仍由原任务清单及其访问守卫管理。
CREATE TABLE ai_scope_authorization (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purpose VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider VARCHAR(120) NOT NULL,
    model VARCHAR(200) NOT NULL,
    protocol VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    config_version VARCHAR(80) NOT NULL,
    price_version VARCHAR(80) NOT NULL,
    source_task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_manifest_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    configuration_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    runtime_epoch CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (owner_id,id),
    KEY ix_ai_scope_authorization_active (owner_id,runtime_epoch,state,expires_at),
    KEY ix_ai_scope_authorization_match (owner_id,purpose,scope_hash,configuration_fingerprint,state,expires_at),
    KEY ix_ai_scope_authorization_source (owner_id,source_task_id),
    CONSTRAINT fk_ai_scope_authorization_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_scope_authorization_source FOREIGN KEY (owner_id,source_task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_scope_authorization_purpose CHECK (purpose IN (
        'KNOWLEDGE_DRAFT','QUESTION_DRAFT','FOLLOW_UP','ANSWER_EVALUATION','MATERIAL_QA','EXPRESSION_DRAFT',
        'PREPARATION_PLAN','RELATION_DRAFT','ANSWER_COMPARISON','MEMORY_CARD_DRAFT','JD_REQUIREMENT_DRAFT','FACT_DRAFT','ANSWER_REWRITE')),
    CONSTRAINT ck_ai_scope_authorization_protocol CHECK (protocol IN ('CHAT_COMPLETIONS','RESPONSES')),
    CONSTRAINT ck_ai_scope_authorization_metadata CHECK (CHAR_LENGTH(TRIM(provider)) BETWEEN 1 AND 120
        AND CHAR_LENGTH(TRIM(model)) BETWEEN 1 AND 200 AND CHAR_LENGTH(TRIM(config_version)) BETWEEN 1 AND 80
        AND CHAR_LENGTH(TRIM(price_version)) BETWEEN 1 AND 80),
    CONSTRAINT ck_ai_scope_authorization_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_ai_scope_authorization_lifecycle CHECK (expires_at>created_at
        AND ((state='ACTIVE' AND revision=0 AND revoked_at IS NULL)
            OR (state='REVOKED' AND revision=1 AND revoked_at IS NOT NULL AND revoked_at>=created_at)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 旧尝试的空绑定保持单次授权；撤回不会改写既有尝试的授权修订或费用。
ALTER TABLE ai_attempt
    ADD COLUMN scope_authorization_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN scope_authorization_revision BIGINT NULL,
    ADD KEY ix_ai_attempt_scope_authorization (owner_id,scope_authorization_id),
    ADD CONSTRAINT fk_ai_attempt_scope_authorization FOREIGN KEY (owner_id,scope_authorization_id)
        REFERENCES ai_scope_authorization (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT ck_ai_attempt_scope_authorization CHECK (
        (scope_authorization_id IS NULL AND scope_authorization_revision IS NULL)
        OR (scope_authorization_id IS NOT NULL AND scope_authorization_revision IS NOT NULL
            AND scope_authorization_revision BETWEEN 0 AND 9007199254740991));
