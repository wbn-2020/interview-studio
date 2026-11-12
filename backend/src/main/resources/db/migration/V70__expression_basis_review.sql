-- 与表达依据复核后端及界面配套在服务器显式发布；本机不执行。
-- 升级前表达没有可证明的历史基线，不回填记录，也不改写旧正文、manifest、摘要或回执。
ALTER TABLE preparation_item
    ADD UNIQUE KEY uk_preparation_fact_project (owner_id,id,project_id);

CREATE TABLE expression_basis_review (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT NOT NULL,
    revision BIGINT NOT NULL,
    kind VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NULL,
    item_revision BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_expression_basis_revision (owner_id,item_id,revision),
    UNIQUE KEY uk_expression_basis_identity (owner_id,id,item_id,version_id),
    KEY ix_expression_basis_version (owner_id,item_id,version_id,revision),
    CONSTRAINT fk_expression_basis_version FOREIGN KEY (owner_id,item_id,version_id)
        REFERENCES preparation_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_expression_basis_number CHECK (version_number > 0),
    CONSTRAINT ck_expression_basis_revision CHECK (revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_expression_basis_item_revision CHECK (item_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_expression_basis_kind CHECK (
        (kind='SAVED_BASIS' AND reason IS NULL)
        OR (kind='CONFIRMED_BASIS' AND reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 1000)),
    CONSTRAINT ck_expression_basis_time CHECK (
        created_at BETWEEN '1000-01-01 00:00:00.000000' AND '9999-12-31 23:59:59.999999')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 有序快照只保存固定身份、标题与当时 CURRENT 的代次，不读取或复制事实正文。
CREATE TABLE expression_basis_review_fact (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    fact_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fact_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(200) NOT NULL,
    version_number INT NOT NULL,
    project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_generation BIGINT NOT NULL,
    project_generation BIGINT NOT NULL,
    PRIMARY KEY (owner_id,review_id,ordinal),
    UNIQUE KEY uk_expression_basis_fact (owner_id,review_id,fact_version_id),
    CONSTRAINT fk_expression_basis_fact_review FOREIGN KEY (owner_id,review_id,item_id,version_id)
        REFERENCES expression_basis_review (owner_id,id,item_id,version_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_expression_basis_fact_version FOREIGN KEY (owner_id,fact_item_id,fact_version_id)
        REFERENCES preparation_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_expression_basis_fact_project FOREIGN KEY (owner_id,fact_item_id,project_id)
        REFERENCES preparation_item (owner_id,id,project_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_expression_basis_project FOREIGN KEY (owner_id,project_id)
        REFERENCES preparation_item (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_expression_basis_fact_ordinal CHECK (ordinal BETWEEN 1 AND 50),
    CONSTRAINT ck_expression_basis_fact_number CHECK (version_number > 0),
    CONSTRAINT ck_expression_basis_fact_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_expression_basis_fact_generation CHECK (
        source_generation BETWEEN 0 AND 9007199254740991 AND project_generation BETWEEN 0 AND 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
