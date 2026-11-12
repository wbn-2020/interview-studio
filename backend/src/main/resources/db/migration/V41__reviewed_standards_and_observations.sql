-- 由服务器发布步骤显式执行；审阅与观察保留确切版本，历史和直接来源事件只追加。
CREATE TABLE standard_review (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_standard_review_owner (owner_id,id),
    UNIQUE KEY uk_standard_review_identity (owner_id,id,question_id,question_version_id),
    KEY ix_standard_review_directory (owner_id,question_id,question_version_id,id),
    KEY ix_standard_review_status (owner_id,question_id,question_version_id,status,id),
    CONSTRAINT fk_standard_review_question FOREIGN KEY (owner_id,question_id,question_version_id)
        REFERENCES question_version (owner_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_standard_review_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_standard_review_status CHECK (status IN ('ACTIVE','WITHDRAWN')),
    CONSTRAINT ck_standard_review_clock CHECK (updated_at>=created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 当前修订指针由同一 owner 事务维护；固定题目身份由复合外键约束，避免首修订插入循环。
CREATE TABLE standard_review_revision (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    change_reason VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,review_id,revision),
    CONSTRAINT fk_standard_revision_identity FOREIGN KEY (owner_id,review_id,question_id,question_version_id)
        REFERENCES standard_review (owner_id,id,question_id,question_version_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_standard_revision_number CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_standard_revision_change CHECK (
        (revision=0 AND change_kind='CREATED' AND change_reason IS NULL)
        OR (revision>0 AND change_kind IN ('REVISED','WITHDRAWN') AND change_reason IS NOT NULL
            AND CHAR_LENGTH(TRIM(change_reason)) BETWEEN 1 AND 2000)),
    CONSTRAINT ck_standard_revision_snapshot CHECK (JSON_VALID(snapshot_json)
        AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json)<=262144)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- checkpoint 属于确切题目版本的 JSON，服务核原始顺序与身份；此表固定每次审阅覆盖范围。
CREATE TABLE standard_review_checkpoint (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    checkpoint_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position_index INT NOT NULL,
    decision VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (owner_id,review_id,revision,checkpoint_id),
    UNIQUE KEY uk_standard_checkpoint_position (owner_id,review_id,revision,position_index),
    KEY ix_standard_checkpoint_history (owner_id,review_id,checkpoint_id,revision),
    CONSTRAINT fk_standard_checkpoint_revision FOREIGN KEY (owner_id,review_id,revision)
        REFERENCES standard_review_revision (owner_id,review_id,revision) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_standard_checkpoint_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_standard_checkpoint_position CHECK (position_index BETWEEN 0 AND 29),
    CONSTRAINT ck_standard_checkpoint_decision CHECK (decision IN ('SUPPORTED_WITHIN_SCOPE','DISPUTED','INSUFFICIENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- role_mask 的 1/2 分别表示实际审阅记录/技术依据；外部摘录仅保存在不可变修订 JSON。
CREATE TABLE standard_review_evidence (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position_index INT NOT NULL,
    role_mask TINYINT NOT NULL,
    basis_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    knowledge_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    excerpt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (owner_id,review_id,revision,evidence_id),
    UNIQUE KEY uk_standard_evidence_position (owner_id,review_id,revision,position_index),
    KEY ix_standard_evidence_knowledge (owner_id,basis_kind,knowledge_item_id,review_id,revision),
    KEY ix_standard_evidence_excerpt (owner_id,excerpt_id,review_id,revision),
    CONSTRAINT fk_standard_evidence_revision FOREIGN KEY (owner_id,review_id,revision)
        REFERENCES standard_review_revision (owner_id,review_id,revision) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_standard_evidence_knowledge FOREIGN KEY (owner_id,knowledge_item_id,knowledge_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_standard_evidence_excerpt FOREIGN KEY (owner_id,excerpt_id)
        REFERENCES material_excerpt (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_standard_evidence_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_standard_evidence_position CHECK (position_index BETWEEN 0 AND 11),
    CONSTRAINT ck_standard_evidence_roles CHECK (role_mask BETWEEN 1 AND 3),
    CONSTRAINT ck_standard_evidence_basis CHECK (
        (basis_kind='KNOWLEDGE' AND knowledge_item_id IS NOT NULL AND knowledge_version_id IS NOT NULL AND excerpt_id IS NULL)
        OR (basis_kind='MATERIAL_EXCERPT' AND knowledge_item_id IS NULL AND knowledge_version_id IS NULL AND excerpt_id IS NOT NULL)
        OR (basis_kind='EXTERNAL_QUOTE' AND knowledge_item_id IS NULL AND knowledge_version_id IS NULL AND excerpt_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reviewed_observation (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    training_question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkpoint_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reviewed_observation_owner (owner_id,id),
    UNIQUE KEY uk_reviewed_observation_checkpoint (owner_id,id,checkpoint_id),
    KEY ix_reviewed_observation_directory (owner_id,answer_id,id),
    KEY ix_reviewed_observation_status (owner_id,answer_id,status,id),
    CONSTRAINT fk_reviewed_observation_answer FOREIGN KEY (owner_id,session_id,training_question_id,answer_id)
        REFERENCES training_answer (owner_id,session_id,question_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_reviewed_observation_revision CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_reviewed_observation_status CHECK (status IN ('ACTIVE','WITHDRAWN')),
    CONSTRAINT ck_reviewed_observation_clock CHECK (updated_at>=created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 观察的检查项身份固定；每次修订保留当时确切审阅修订，不能随标准编辑替换旧引用。
CREATE TABLE reviewed_observation_revision (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    observation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    checkpoint_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    standard_review_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    standard_review_revision BIGINT NOT NULL,
    change_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    change_reason VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,observation_id,revision),
    KEY ix_reviewed_observation_standard (owner_id,standard_review_id,standard_review_revision,observation_id,revision),
    CONSTRAINT fk_reviewed_revision_observation FOREIGN KEY (owner_id,observation_id,checkpoint_id)
        REFERENCES reviewed_observation (owner_id,id,checkpoint_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_reviewed_revision_checkpoint FOREIGN KEY (owner_id,standard_review_id,standard_review_revision,checkpoint_id)
        REFERENCES standard_review_checkpoint (owner_id,review_id,revision,checkpoint_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_reviewed_revision_number CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_reviewed_revision_standard CHECK (standard_review_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_reviewed_revision_change CHECK (
        (revision=0 AND change_kind='CREATED' AND change_reason IS NULL)
        OR (revision>0 AND change_kind IN ('REVISED','WITHDRAWN') AND change_reason IS NOT NULL
            AND CHAR_LENGTH(TRIM(change_reason)) BETWEEN 1 AND 2000)),
    CONSTRAINT ck_reviewed_revision_snapshot CHECK (JSON_VALID(snapshot_json)
        AND JSON_TYPE(CAST(snapshot_json AS JSON))='OBJECT' AND OCTET_LENGTH(snapshot_json)<=262144)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 缺少行表示来源变更号 0；只有真实来源变更才插入第 1 代，读取不能创建基线。
CREATE TABLE review_basis_generation (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,source_kind,source_id),
    CONSTRAINT fk_review_basis_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_review_basis_kind CHECK (source_kind IN ('QUESTION','KNOWLEDGE','MATERIAL_ATTACHMENT')),
    CONSTRAINT ck_review_basis_generation CHECK (generation BETWEEN 1 AND 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE review_basis_change (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation BIGINT NOT NULL,
    change_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    current_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    previous_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,source_kind,source_id,generation),
    CONSTRAINT fk_review_basis_change_source FOREIGN KEY (owner_id,source_kind,source_id)
        REFERENCES review_basis_generation (owner_id,source_kind,source_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_review_basis_change_generation CHECK (generation BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_review_basis_change_identity CHECK (
        (source_kind='QUESTION' AND previous_version_id IS NOT NULL AND current_version_id IS NOT NULL AND (
            (change_kind='QUESTION_VERSION_CHANGED' AND previous_version_id<>current_version_id
                AND previous_status='ACTIVE' AND current_status='ACTIVE')
            OR (change_kind='QUESTION_ARCHIVED' AND previous_version_id=current_version_id
                AND previous_status='ACTIVE' AND current_status='ARCHIVED')
            OR (change_kind='QUESTION_RESTORED' AND previous_version_id=current_version_id
                AND previous_status='ARCHIVED' AND current_status='ACTIVE')))
        OR (source_kind='KNOWLEDGE' AND previous_version_id IS NOT NULL AND current_version_id IS NOT NULL AND (
            (change_kind='KNOWLEDGE_BODY_CHANGED' AND previous_version_id<>current_version_id
                AND previous_status='ACTIVE' AND current_status='ACTIVE')
            OR (change_kind='KNOWLEDGE_ARCHIVED' AND previous_version_id=current_version_id
                AND previous_status='ACTIVE' AND current_status='ARCHIVED')
            OR (change_kind='KNOWLEDGE_RESTORED' AND previous_version_id=current_version_id
                AND previous_status='ARCHIVED' AND current_status='ACTIVE')))
        OR (source_kind='MATERIAL_ATTACHMENT' AND change_kind='MATERIAL_BASIS_CHANGED'
            AND previous_version_id IS NULL AND current_version_id IS NULL
            AND previous_status IN ('RESERVED','READY') AND current_status IN ('RESERVED','READY')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
