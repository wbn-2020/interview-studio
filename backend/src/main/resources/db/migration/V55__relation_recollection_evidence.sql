-- 回忆证据只追加固定版本与原文选区；正式边删除后保留原关系身份，不把回忆升级为正式答案。
CREATE TABLE knowledge_relation_recollection_evidence (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    relation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    relation_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    recollection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT NOT NULL,
    source_revision BIGINT NOT NULL,
    source_count INT NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,id),
    KEY ix_relation_recollection_evidence_relation (owner_id,relation_id,id),
    KEY ix_relation_recollection_evidence_version (owner_id,recollection_id,version_id,id),
    CONSTRAINT fk_relation_recollection_evidence_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_relation_recollection_evidence_version FOREIGN KEY (owner_id,recollection_id,version_id)
        REFERENCES review_recollection_version (owner_id,recollection_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_relation_recollection_evidence_type CHECK (relation_type IN ('PRECEDES','PART_OF','APPLIED_IN','RELATED','CONTRASTS')),
    CONSTRAINT ck_relation_recollection_evidence_endpoints CHECK (source_id<>target_id
        AND (relation_type NOT IN ('RELATED','CONTRASTS') OR source_id<target_id)),
    CONSTRAINT ck_relation_recollection_evidence_reason CHECK (CHAR_LENGTH(reason) BETWEEN 1 AND 1000),
    CONSTRAINT ck_relation_recollection_evidence_source CHECK (version_number BETWEEN 1 AND 1000
        AND source_revision BETWEEN 0 AND 1000 AND version_number<=source_revision+1 AND source_count BETWEEN 1 AND 8),
    CONSTRAINT ck_relation_recollection_evidence_snapshot CHECK (JSON_VALID(snapshot_json)
        AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json)<=1048576)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
