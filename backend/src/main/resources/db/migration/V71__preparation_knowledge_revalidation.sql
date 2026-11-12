-- 配套服务器发布时显式执行；本机不执行。仅记录接入后的真实事件，不回填旧更正、改写清单或回执。
ALTER TABLE ai_preparation_source
    ADD UNIQUE KEY uk_ai_preparation_revalidation (owner_id,task_id,source_item_id,source_version_id);

CREATE TABLE preparation_knowledge_event (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    root_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    root_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    root_title VARCHAR(200) NOT NULL,
    root_project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    change_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_version_number INT NOT NULL,
    previous_generation BIGINT NOT NULL,
    previous_revision BIGINT NOT NULL,
    previous_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_verification VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    current_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_version_number INT NOT NULL,
    current_generation BIGINT NOT NULL,
    current_revision BIGINT NOT NULL,
    current_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_verification VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_preparation_knowledge_event (owner_id,id),
    KEY ix_preparation_knowledge_root (owner_id,root_item_id,created_at,id),
    KEY ix_preparation_knowledge_clock (owner_id,created_at),
    CONSTRAINT fk_pk_event_previous FOREIGN KEY (owner_id,root_item_id,previous_version_id)
        REFERENCES preparation_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pk_event_current FOREIGN KEY (owner_id,root_item_id,current_version_id)
        REFERENCES preparation_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pk_event_fact_project FOREIGN KEY (owner_id,root_item_id,root_project_id)
        REFERENCES preparation_item (owner_id,id,project_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pk_event_project FOREIGN KEY (owner_id,root_project_id)
        REFERENCES preparation_item (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_pk_event_identity CHECK (
        root_kind IN ('TARGET','PROJECT','FACT','EXPRESSION') AND CHAR_LENGTH(TRIM(root_title)) BETWEEN 1 AND 200
        AND ((root_kind='FACT' AND root_project_id IS NOT NULL
            AND previous_verification IS NOT NULL AND previous_verification IN ('UNVERIFIED','CONFIRMED','WITHDRAWN')
            AND current_verification IS NOT NULL AND current_verification IN ('UNVERIFIED','CONFIRMED','WITHDRAWN'))
          OR (root_kind<>'FACT' AND root_project_id IS NULL AND previous_verification IS NULL AND current_verification IS NULL))),
    CONSTRAINT ck_pk_event_generation CHECK (
        previous_generation BETWEEN 0 AND 9007199254740990 AND current_generation=previous_generation+1
        AND previous_revision BETWEEN 0 AND 9007199254740990 AND current_revision=previous_revision+1),
    CONSTRAINT ck_pk_event_status CHECK (previous_status IN ('ACTIVE','ARCHIVED') AND current_status IN ('ACTIVE','ARCHIVED')),
    CONSTRAINT ck_pk_event_change CHECK (
        previous_version_number>0 AND current_version_number>0
        AND ((change_kind='CONTENT_SAVED' AND previous_version_id<>current_version_id
              AND current_version_number=previous_version_number+1)
          OR (change_kind IN ('STATUS_SET','FACT_DECIDED','EXPRESSION_BASIS_CONFIRMED')
              AND previous_version_id=current_version_id AND current_version_number=previous_version_number))
        AND (change_kind<>'FACT_DECIDED' OR root_kind='FACT')
        AND (change_kind<>'EXPRESSION_BASIS_CONFIRMED' OR root_kind='EXPRESSION')),
    CONSTRAINT ck_pk_event_time CHECK (created_at BETWEEN '1000-01-01 00:00:00.000000' AND '9999-12-31 23:59:59.999999')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE preparation_knowledge_impact (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_domain VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preparation_source_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    preparation_source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    knowledge_source_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    knowledge_source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    dependency_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    via_fact_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    via_fact_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    depth INT NOT NULL,
    parent_impact_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    propagation_pending BOOLEAN NOT NULL DEFAULT TRUE,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    choice VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(1000) NULL,
    resolution_target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    resolution_target_revision BIGINT NULL,
    decided_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pk_preparation_edge (owner_id,event_id,task_id,preparation_source_item_id,preparation_source_version_id),
    UNIQUE KEY uk_pk_knowledge_edge (owner_id,event_id,task_id,knowledge_source_item_id,knowledge_source_version_id),
    UNIQUE KEY uk_pk_parent (owner_id,event_id,id,target_item_id),
    KEY ix_pk_target (owner_id,target_item_id,created_at,id),
    KEY ix_pk_pending (owner_id,target_item_id,revision,created_at,id),
    KEY ix_pk_target_version (owner_id,target_item_id,target_version_id,created_at),
    KEY ix_pk_expand (owner_id,event_id,propagation_pending,depth,task_id,id),
    CONSTRAINT fk_pk_impact_event FOREIGN KEY (owner_id,event_id)
        REFERENCES preparation_knowledge_event (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pk_preparation_path FOREIGN KEY (owner_id,task_id,preparation_source_item_id,preparation_source_version_id)
        REFERENCES ai_preparation_source (owner_id,task_id,source_item_id,source_version_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pk_preparation_version FOREIGN KEY (owner_id,preparation_source_item_id,preparation_source_version_id)
        REFERENCES preparation_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pk_knowledge_path FOREIGN KEY (owner_id,task_id,knowledge_source_item_id,knowledge_source_version_id)
        REFERENCES ai_knowledge_source (owner_id,task_id,source_item_id,source_version_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pk_knowledge_version FOREIGN KEY (owner_id,knowledge_source_item_id,knowledge_source_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pk_via_fact FOREIGN KEY (owner_id,via_fact_item_id,via_fact_version_id)
        REFERENCES preparation_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pk_adoption FOREIGN KEY (owner_id,task_id,target_item_id,target_version_id)
        REFERENCES ai_candidate_decision (owner_id,task_id,target_item_id,target_version_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pk_target FOREIGN KEY (owner_id,target_item_id,target_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pk_parent FOREIGN KEY (owner_id,event_id,parent_impact_id,knowledge_source_item_id)
        REFERENCES preparation_knowledge_impact (owner_id,event_id,id,target_item_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pk_resolution FOREIGN KEY (owner_id,target_item_id,resolution_target_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_pk_source CHECK (
        (source_domain='PREPARATION' AND source_kind IN ('TARGET','PROJECT','FACT','EXPRESSION')
            AND preparation_source_item_id IS NOT NULL AND preparation_source_version_id IS NOT NULL
            AND knowledge_source_item_id IS NULL AND knowledge_source_version_id IS NULL
            AND dependency_kind IN ('DIRECT','PROJECT_FACT','FACT_EXPRESSION','PROJECT_EXPRESSION')
            AND depth=1 AND parent_impact_id IS NULL)
        OR (source_domain='KNOWLEDGE' AND source_kind='KNOWLEDGE'
            AND knowledge_source_item_id IS NOT NULL AND knowledge_source_version_id IS NOT NULL
            AND preparation_source_item_id IS NULL AND preparation_source_version_id IS NULL
            AND dependency_kind='KNOWLEDGE' AND depth BETWEEN 2 AND 2147483647
            AND parent_impact_id IS NOT NULL AND parent_impact_id<>id)),
    CONSTRAINT ck_pk_bridge CHECK (
        (dependency_kind IN ('FACT_EXPRESSION','PROJECT_EXPRESSION') AND source_kind='EXPRESSION'
            AND via_fact_item_id IS NOT NULL AND via_fact_version_id IS NOT NULL)
        OR (dependency_kind NOT IN ('FACT_EXPRESSION','PROJECT_EXPRESSION')
            AND via_fact_item_id IS NULL AND via_fact_version_id IS NULL)),
    CONSTRAINT ck_pk_expand CHECK (propagation_pending IN (0,1)),
    CONSTRAINT ck_pk_revision CHECK (revision BETWEEN 0 AND 1),
    CONSTRAINT ck_pk_resolution CHECK (
        (revision=0 AND choice IS NULL AND reason IS NULL AND resolution_target_version_id IS NULL
            AND resolution_target_revision IS NULL AND decided_at IS NULL)
        OR (revision=1 AND choice IS NOT NULL AND choice IN ('KEEP_CURRENT','PUBLISHED_UPDATE','ARCHIVED')
            AND reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 1000
            AND resolution_target_version_id IS NOT NULL AND resolution_target_revision IS NOT NULL
            AND resolution_target_revision BETWEEN 0 AND 9007199254740991 AND decided_at IS NOT NULL AND decided_at>=created_at)),
    CONSTRAINT ck_pk_time CHECK (
        created_at BETWEEN '1000-01-01 00:00:00.000000' AND '9999-12-31 23:59:59.999999'
        AND (decided_at IS NULL OR decided_at BETWEEN '1000-01-01 00:00:00.000000' AND '9999-12-31 23:59:59.999999'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
