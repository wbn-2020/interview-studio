-- 与前后端配套在服务器显式发布；本机不执行。MySQL DDL 会隐式提交，部署需先备份并逐句核验。
-- 只登记接入后的真实根事件，不从旧版本差异回填事件，也不改写历史采用或回忆记录。
ALTER TABLE ai_candidate_decision
    ADD UNIQUE KEY uk_ai_decision_revalidation (owner_id,task_id,target_item_id,target_version_id);
ALTER TABLE ai_knowledge_source
    ADD UNIQUE KEY uk_ai_source_revalidation (owner_id,task_id,source_item_id,source_version_id);

CREATE TABLE knowledge_revalidation_event (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    root_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    root_previous_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    root_current_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_revalidation_root_event (owner_id,id,root_item_id),
    KEY ix_knowledge_revalidation_event_root (owner_id,root_item_id,created_at,id),
    CONSTRAINT fk_knowledge_revalidation_previous FOREIGN KEY (owner_id,root_item_id,root_previous_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_knowledge_revalidation_current FOREIGN KEY (owner_id,root_item_id,root_current_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_knowledge_revalidation_event_kind CHECK (
        (change_kind='BODY_CHANGED' AND root_previous_version_id<>root_current_version_id)
        OR (change_kind='ARCHIVED' AND root_previous_version_id=root_current_version_id)),
    CONSTRAINT ck_knowledge_revalidation_event_time CHECK (
        created_at BETWEEN '1000-01-01 00:00:00.000000' AND '9999-12-31 23:59:59.999999')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 固定采用身份保持不变，处理只把 revision 从 0 推进至 1；更晚根事件产生另一条独立影响。
CREATE TABLE knowledge_revalidation_impact (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    root_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    choice VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(1000) NULL,
    resolution_target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    resolution_target_revision BIGINT NULL,
    decided_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_revalidation_path (owner_id,event_id,task_id,root_item_id,source_version_id),
    KEY ix_knowledge_revalidation_impact_root (owner_id,root_item_id,created_at,id),
    KEY ix_knowledge_revalidation_impact_target (owner_id,target_item_id,created_at,id),
    KEY ix_knowledge_revalidation_impact_pending (owner_id,target_item_id,revision,created_at,id),
    KEY ix_knowledge_revalidation_impact_version (owner_id,target_item_id,target_version_id,created_at),
    CONSTRAINT fk_knowledge_revalidation_impact_event FOREIGN KEY (owner_id,event_id,root_item_id)
        REFERENCES knowledge_revalidation_event (owner_id,id,root_item_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_knowledge_revalidation_source_path FOREIGN KEY (owner_id,task_id,root_item_id,source_version_id)
        REFERENCES ai_knowledge_source (owner_id,task_id,source_item_id,source_version_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_knowledge_revalidation_source_version FOREIGN KEY (owner_id,root_item_id,source_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_knowledge_revalidation_adoption FOREIGN KEY (owner_id,task_id,target_item_id,target_version_id)
        REFERENCES ai_candidate_decision (owner_id,task_id,target_item_id,target_version_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_knowledge_revalidation_target_version FOREIGN KEY (owner_id,target_item_id,target_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_knowledge_revalidation_resolution_version FOREIGN KEY (owner_id,target_item_id,resolution_target_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_knowledge_revalidation_impact_revision CHECK (revision BETWEEN 0 AND 1),
    CONSTRAINT ck_knowledge_revalidation_resolution CHECK (
        (revision=0 AND choice IS NULL AND reason IS NULL AND resolution_target_version_id IS NULL
            AND resolution_target_revision IS NULL AND decided_at IS NULL)
        OR (revision=1 AND choice IS NOT NULL AND choice IN ('KEEP_CURRENT','PUBLISHED_UPDATE','ARCHIVED')
            AND reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 1000
            AND resolution_target_version_id IS NOT NULL AND resolution_target_revision IS NOT NULL
            AND resolution_target_revision BETWEEN 0 AND 9007199254740991 AND decided_at IS NOT NULL
            AND decided_at>=created_at)),
    CONSTRAINT ck_knowledge_revalidation_impact_time CHECK (
        created_at BETWEEN '1000-01-01 00:00:00.000000' AND '9999-12-31 23:59:59.999999'
        AND (decided_at IS NULL OR decided_at BETWEEN '1000-01-01 00:00:00.000000' AND '9999-12-31 23:59:59.999999'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
