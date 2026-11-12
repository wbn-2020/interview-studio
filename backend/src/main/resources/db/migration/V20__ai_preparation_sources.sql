-- 服务器显式升级；创建授权任务时保留准备版本关系，采用历史再按采用决定筛选。
CREATE TABLE ai_preparation_source (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_number INT NOT NULL,
    source_title VARCHAR(200) NOT NULL,
    ordinal INT NOT NULL,
    PRIMARY KEY (owner_id,task_id,source_item_id),
    UNIQUE KEY uk_ai_preparation_source_ordinal (owner_id,task_id,ordinal),
    KEY ix_ai_preparation_source_version (owner_id,source_item_id,source_version_id,task_id),
    CONSTRAINT fk_ai_preparation_source_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_preparation_source_version FOREIGN KEY (owner_id,source_item_id,source_version_id)
        REFERENCES preparation_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_preparation_source_kind CHECK (source_kind IN ('TARGET','PROJECT','FACT')),
    CONSTRAINT ck_ai_preparation_source_number CHECK (source_version_number > 0),
    CONSTRAINT ck_ai_preparation_source_title CHECK (CHAR_LENGTH(TRIM(source_title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_ai_preparation_source_ordinal CHECK (ordinal BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
