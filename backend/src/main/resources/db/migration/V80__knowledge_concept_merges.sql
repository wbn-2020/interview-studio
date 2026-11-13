-- 合并保存旧身份与当时版本；归档源的历史引用不被重绑，正文仍由原知识版本负责。
CREATE TABLE knowledge_concept_merge (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_title VARCHAR(200) NOT NULL,
    target_title VARCHAR(200) NOT NULL,
    source_revision_before BIGINT NOT NULL,
    target_revision_before BIGINT NOT NULL,
    target_revision_after BIGINT NOT NULL,
    source_organization_revision BIGINT NOT NULL,
    target_organization_revision BIGINT NOT NULL,
    layout_revision_before BIGINT NOT NULL,
    layout_revision_after BIGINT NOT NULL,
    choices_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,id),
    UNIQUE KEY uk_concept_merge_source (owner_id,source_item_id),
    KEY ix_concept_merge_target (owner_id,target_item_id,id),
    CONSTRAINT fk_concept_merge_source_version FOREIGN KEY (owner_id,source_item_id,source_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_concept_merge_target_version FOREIGN KEY (owner_id,target_item_id,target_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_concept_merge_distinct CHECK (source_item_id<>target_item_id),
    CONSTRAINT ck_concept_merge_titles CHECK (CHAR_LENGTH(source_title) BETWEEN 1 AND 200 AND CHAR_LENGTH(target_title) BETWEEN 1 AND 200),
    CONSTRAINT ck_concept_merge_revisions CHECK (
        source_revision_before BETWEEN 0 AND 9007199254740990
        AND target_revision_before BETWEEN 0 AND 9007199254740990
        AND target_revision_after=target_revision_before+1
        AND source_organization_revision BETWEEN 0 AND 9007199254740991
        AND target_organization_revision BETWEEN 0 AND 9007199254740990
        AND layout_revision_before BETWEEN 0 AND 9007199254740990
        AND layout_revision_after BETWEEN layout_revision_before+1 AND 9007199254740991),
    CONSTRAINT ck_concept_merge_choices CHECK (JSON_TYPE(choices_json)='OBJECT' AND OCTET_LENGTH(choices_json)<=131072)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 不以外键指向可移除的 live 关系；原出处保留原 ID，新去向单独登记，避免冒充 AI 生成身份。
CREATE TABLE knowledge_concept_merge_relation (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merge_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    old_relation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    relation_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_relation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    outcome VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,merge_id,old_relation_id),
    KEY ix_concept_merge_relation_old (owner_id,old_relation_id,merge_id),
    KEY ix_concept_merge_relation_target (owner_id,target_relation_id,merge_id),
    CONSTRAINT fk_concept_merge_relation_record FOREIGN KEY (owner_id,merge_id)
        REFERENCES knowledge_concept_merge (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_concept_merge_relation_type CHECK (relation_type IN ('PRECEDES','PART_OF','APPLIED_IN','RELATED','CONTRASTS')),
    CONSTRAINT ck_concept_merge_relation_endpoints CHECK (source_id<>target_id
        AND (relation_type NOT IN ('RELATED','CONTRASTS') OR source_id<target_id)),
    CONSTRAINT ck_concept_merge_relation_outcome CHECK (
        (outcome IN ('REPLACED','DEDUPLICATED') AND target_relation_id IS NOT NULL)
        OR (outcome='SELF_REMOVED' AND target_relation_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
