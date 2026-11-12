-- 服务器显式升级；资料候选关系保留确切摘录，采用历史从原任务清单读取固定元数据。
CREATE TABLE ai_material_draft_source (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    excerpt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    PRIMARY KEY (owner_id,task_id,excerpt_id),
    UNIQUE KEY uk_ai_material_draft_ordinal (owner_id,task_id,ordinal),
    KEY ix_ai_material_draft_excerpt (owner_id,excerpt_id,task_id),
    CONSTRAINT fk_ai_material_draft_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_material_draft_excerpt FOREIGN KEY (owner_id,excerpt_id)
        REFERENCES material_excerpt (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_material_draft_ordinal CHECK (ordinal BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
