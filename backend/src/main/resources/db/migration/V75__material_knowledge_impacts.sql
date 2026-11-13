-- 配套后端、读守卫及界面在服务器显式发布；本机不执行。MySQL DDL 隐式提交，先备份并逐句核验。
-- 仅记录新提交的真实 V74 更正所产生的完整采用闭包，不回填旧根、不改历史正文、清单或回执。
ALTER TABLE material_excerpt_correction
    ADD UNIQUE KEY uk_material_correction_owner (owner_id,id);

CREATE TABLE material_knowledge_impact (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correction_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_domain VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    material_source_excerpt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    knowledge_source_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    knowledge_source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    depth INT NOT NULL,
    parent_impact_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    choice VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(1000) NULL,
    resolution_target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    resolution_target_revision BIGINT NULL,
    decided_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mk_material_edge (owner_id,correction_id,task_id,material_source_excerpt_id),
    UNIQUE KEY uk_mk_knowledge_edge (owner_id,correction_id,task_id,knowledge_source_item_id,knowledge_source_version_id),
    UNIQUE KEY uk_mk_parent (owner_id,correction_id,id,target_item_id),
    KEY ix_mk_root (owner_id,correction_id,created_at,id),
    KEY ix_mk_target (owner_id,target_item_id,created_at,id),
    KEY ix_mk_pending (owner_id,target_item_id,revision,created_at,id),
    KEY ix_mk_target_version (owner_id,target_item_id,target_version_id,created_at),
    CONSTRAINT fk_mk_correction FOREIGN KEY (owner_id,correction_id)
        REFERENCES material_excerpt_correction (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_mk_material_path FOREIGN KEY (owner_id,task_id,material_source_excerpt_id)
        REFERENCES ai_material_draft_source (owner_id,task_id,excerpt_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_mk_knowledge_path FOREIGN KEY (owner_id,task_id,knowledge_source_item_id,knowledge_source_version_id)
        REFERENCES ai_knowledge_source (owner_id,task_id,source_item_id,source_version_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_mk_knowledge_version FOREIGN KEY (owner_id,knowledge_source_item_id,knowledge_source_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_mk_adoption FOREIGN KEY (owner_id,task_id,target_item_id,target_version_id)
        REFERENCES ai_candidate_decision (owner_id,task_id,target_item_id,target_version_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_mk_target FOREIGN KEY (owner_id,target_item_id,target_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_mk_parent FOREIGN KEY (owner_id,correction_id,parent_impact_id,knowledge_source_item_id)
        REFERENCES material_knowledge_impact (owner_id,correction_id,id,target_item_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_mk_resolution FOREIGN KEY (owner_id,target_item_id,resolution_target_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_mk_source CHECK (
        (source_domain='MATERIAL_EXCERPT' AND material_source_excerpt_id IS NOT NULL
            AND knowledge_source_item_id IS NULL AND knowledge_source_version_id IS NULL
            AND depth=1 AND parent_impact_id IS NULL)
        OR (source_domain='KNOWLEDGE' AND material_source_excerpt_id IS NULL
            AND knowledge_source_item_id IS NOT NULL AND knowledge_source_version_id IS NOT NULL
            AND depth BETWEEN 2 AND 500 AND parent_impact_id IS NOT NULL AND parent_impact_id<>id)),
    CONSTRAINT ck_mk_revision CHECK (revision BETWEEN 0 AND 1),
    CONSTRAINT ck_mk_resolution CHECK (
        (revision=0 AND choice IS NULL AND reason IS NULL AND resolution_target_version_id IS NULL
            AND resolution_target_revision IS NULL AND decided_at IS NULL)
        OR (revision=1 AND choice IS NOT NULL AND choice IN ('KEEP_CURRENT','PUBLISHED_UPDATE','ARCHIVED')
            AND reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 1000
            AND resolution_target_version_id IS NOT NULL AND resolution_target_revision IS NOT NULL
            AND resolution_target_revision BETWEEN 0 AND 9007199254740991 AND decided_at IS NOT NULL
            AND decided_at>=created_at)),
    CONSTRAINT ck_mk_time CHECK (
        created_at BETWEEN '1000-01-01 00:00:00.000000' AND '9999-12-31 23:59:59.999999'
        AND (decided_at IS NULL OR decided_at BETWEEN '1000-01-01 00:00:00.000000' AND '9999-12-31 23:59:59.999999'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
