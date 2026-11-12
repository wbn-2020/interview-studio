-- 服务器发布时显式执行；候选决定、知识发布与来源留痕在同一 owner 命令事务中提交。
CREATE TABLE ai_candidate_decision (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,task_id),
    UNIQUE KEY uk_ai_decision_target (owner_id,target_item_id,target_version_id),
    CONSTRAINT fk_ai_decision_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_decision_target FOREIGN KEY (owner_id,target_item_id,target_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_decision_state CHECK (
        (status='ADOPTED' AND mode IS NOT NULL AND mode IN ('NEW','EXISTING')
            AND target_item_id IS NOT NULL AND target_version_id IS NOT NULL)
        OR (status='IGNORED' AND mode IS NULL AND target_item_id IS NULL AND target_version_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 保存外发清单的历史身份信息；这是采用来源，不生成概念语义边或新的逐句核验结论。
CREATE TABLE ai_knowledge_source (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_number INT NOT NULL,
    source_title VARCHAR(200) NOT NULL,
    ordinal INT NOT NULL,
    PRIMARY KEY (owner_id,task_id,source_item_id),
    UNIQUE KEY uk_ai_source_ordinal (owner_id,task_id,ordinal),
    KEY ix_ai_source_version (owner_id,source_item_id,source_version_id,task_id),
    CONSTRAINT fk_ai_source_decision FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_candidate_decision (owner_id,task_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_source_version FOREIGN KEY (owner_id,source_item_id,source_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_source_number CHECK (source_version_number > 0),
    CONSTRAINT ck_ai_source_title CHECK (CHAR_LENGTH(TRIM(source_title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_ai_source_ordinal CHECK (ordinal > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
