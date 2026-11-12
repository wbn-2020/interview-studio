-- 补充按原关系身份追加；正式边可移除，历史理由和确切版本仍保留。
CREATE TABLE knowledge_relation_supplement (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    relation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    relation_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    origin VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    source_count INT NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    candidate_id VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,id),
    KEY ix_relation_supplement_relation (owner_id,relation_id,id),
    UNIQUE KEY uk_relation_supplement_candidate (owner_id,task_id,candidate_id),
    UNIQUE KEY uk_relation_supplement_decision (owner_id,task_id,candidate_id,relation_id,id),
    CONSTRAINT fk_relation_supplement_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_relation_supplement_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_relation_supplement_type CHECK (relation_type IN ('PRECEDES','PART_OF','APPLIED_IN','RELATED','CONTRASTS')),
    CONSTRAINT ck_relation_supplement_endpoints CHECK (source_id<>target_id
        AND (relation_type NOT IN ('RELATED','CONTRASTS') OR source_id<target_id)),
    CONSTRAINT ck_relation_supplement_reason CHECK (CHAR_LENGTH(reason) BETWEEN 1 AND 1000),
    CONSTRAINT ck_relation_supplement_sources CHECK (source_count BETWEEN 0 AND 5),
    CONSTRAINT ck_relation_supplement_origin CHECK (
        (origin='MANUAL' AND task_id IS NULL AND candidate_id IS NULL)
        OR (origin='AI' AND task_id IS NOT NULL AND candidate_id IS NOT NULL AND source_count BETWEEN 2 AND 5
            AND candidate_id IN ('r1','r2','r3','r4','r5','r6','r7','r8','r9','r10')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_relation_supplement_source (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    supplement_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    source_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_number INT NOT NULL,
    source_title VARCHAR(200) NOT NULL,
    PRIMARY KEY (owner_id,supplement_id,ordinal),
    UNIQUE KEY uk_relation_supplement_source_item (owner_id,supplement_id,source_item_id),
    KEY ix_relation_supplement_source_version (owner_id,source_item_id,source_version_id,supplement_id),
    CONSTRAINT fk_relation_supplement_source_entry FOREIGN KEY (owner_id,supplement_id)
        REFERENCES knowledge_relation_supplement (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_relation_supplement_source_version FOREIGN KEY (owner_id,source_item_id,source_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_relation_supplement_source_ordinal CHECK (ordinal BETWEEN 1 AND 5),
    CONSTRAINT ck_relation_supplement_source_kind CHECK (source_kind IN ('NOTE','CONCEPT')),
    CONSTRAINT ck_relation_supplement_source_number CHECK (source_version_number>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 仅原始新建决定占用原始出处唯一键，后续补充可共同指向原关系。
ALTER TABLE ai_relation_decision
    ADD COLUMN supplement_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN original_relation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN supplement_id IS NULL THEN relation_id ELSE NULL END) STORED,
    DROP INDEX uk_ai_relation_decision_relation,
    ADD UNIQUE KEY uk_ai_relation_decision_original (owner_id,original_relation_id),
    ADD KEY ix_ai_relation_decision_relation (owner_id,relation_id),
    ADD CONSTRAINT ck_ai_relation_decision_supplement CHECK (status='ADOPTED' OR supplement_id IS NULL),
    ADD CONSTRAINT fk_ai_relation_decision_supplement FOREIGN KEY (owner_id,task_id,candidate_id,relation_id,supplement_id)
        REFERENCES knowledge_relation_supplement (owner_id,task_id,candidate_id,relation_id,id)
        ON DELETE RESTRICT ON UPDATE RESTRICT;
