-- 只保存用户显式提交的更正事实，不回填推断声明，不改旧新正文、许可代次或生命周期事件。
-- 服务器发布前先备份并核对迁移状态；MySQL DDL 不随后续失败回滚，本机不执行。
CREATE TABLE material_excerpt_correction (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    excerpt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    replacement_excerpt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_revision BIGINT NOT NULL,
    replacement_revision BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_correction_source (owner_id,excerpt_id),
    KEY ix_material_correction_replacement (owner_id,replacement_excerpt_id),
    CONSTRAINT fk_material_correction_source FOREIGN KEY (owner_id,excerpt_id)
        REFERENCES material_excerpt (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_material_correction_replacement FOREIGN KEY (owner_id,replacement_excerpt_id)
        REFERENCES material_excerpt (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_material_correction_distinct CHECK (excerpt_id<>replacement_excerpt_id),
    CONSTRAINT ck_material_correction_revision CHECK (source_revision BETWEEN 0 AND 9007199254740991
        AND replacement_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_material_correction_reason CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 1000),
    CONSTRAINT ck_material_correction_time CHECK (
        created_at BETWEEN '1000-01-01 00:00:00.000000' AND '9999-12-31 23:59:59.999999')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
