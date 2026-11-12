-- 本地原文证据按确切关系身份追加；移除正式边不撤销历史证据，不触发 AI 外发。
CREATE TABLE knowledge_relation_evidence (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    relation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    relation_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    source_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_count INT NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,id),
    UNIQUE KEY uk_relation_evidence_kind (owner_id,id,source_kind),
    KEY ix_relation_evidence_relation (owner_id,relation_id,id),
    KEY ix_relation_evidence_answer (owner_id,session_id,question_id,answer_id,id),
    CONSTRAINT fk_relation_evidence_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_relation_evidence_answer FOREIGN KEY (owner_id,session_id,question_id,answer_id)
        REFERENCES training_answer (owner_id,session_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_relation_evidence_type CHECK (relation_type IN ('PRECEDES','PART_OF','APPLIED_IN','RELATED','CONTRASTS')),
    CONSTRAINT ck_relation_evidence_endpoints CHECK (source_id<>target_id
        AND (relation_type NOT IN ('RELATED','CONTRASTS') OR source_id<target_id)),
    CONSTRAINT ck_relation_evidence_reason CHECK (CHAR_LENGTH(reason) BETWEEN 1 AND 1000),
    CONSTRAINT ck_relation_evidence_source CHECK (
        (source_kind='MATERIAL' AND source_count BETWEEN 1 AND 5
            AND session_id IS NULL AND question_id IS NULL AND answer_id IS NULL)
        OR (source_kind='TRAINING' AND source_count BETWEEN 1 AND 8
            AND session_id IS NOT NULL AND question_id IS NOT NULL AND answer_id IS NOT NULL)),
    CONSTRAINT ck_relation_evidence_snapshot CHECK (JSON_VALID(snapshot_json)
        AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json)<=1048576)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 关系证据的所选顺序独立固定；引用确切摘录，并由摘录继续约束附件、解析执行和片段。
CREATE TABLE knowledge_relation_evidence_material (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    excerpt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    PRIMARY KEY (owner_id,evidence_id,excerpt_id),
    UNIQUE KEY uk_relation_evidence_material_ordinal (owner_id,evidence_id,ordinal),
    KEY ix_relation_evidence_material_source (owner_id,evidence_id,source_kind),
    KEY ix_relation_evidence_material_excerpt (owner_id,excerpt_id,evidence_id),
    CONSTRAINT fk_relation_evidence_material_entry FOREIGN KEY (owner_id,evidence_id,source_kind)
        REFERENCES knowledge_relation_evidence (owner_id,id,source_kind) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_relation_evidence_material_excerpt FOREIGN KEY (owner_id,excerpt_id)
        REFERENCES material_excerpt (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_relation_evidence_material CHECK (source_kind='MATERIAL' AND ordinal BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
