-- 服务器显式发布并执行；只为新提交的真实更正登记原始直接绑定，不回填或改写历史正文。
CREATE TABLE material_dependency_impact (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correction_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    excerpt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    binding VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    binding_id VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    domain VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_version_number INT NULL,
    target_revision BIGINT NULL,
    target_title VARCHAR(200) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    choice VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(1000) NULL,
    resolution_target_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    resolution_target_revision BIGINT NULL,
    decided_at DATETIME(6) NULL,
    PRIMARY KEY (owner_id,id),
    UNIQUE KEY uk_material_dependency_binding (owner_id,correction_id,binding,binding_id),
    KEY ix_material_dependency_root (owner_id,excerpt_id,created_at,id),
    KEY ix_material_dependency_target (owner_id,domain,target_id,revision,created_at,id),
    CONSTRAINT fk_material_dependency_correction FOREIGN KEY (owner_id,correction_id)
        REFERENCES material_excerpt_correction (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_material_dependency_excerpt FOREIGN KEY (owner_id,excerpt_id)
        REFERENCES material_excerpt (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_material_dependency_task FOREIGN KEY (owner_id,task_id)
        REFERENCES ai_task (owner_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_material_dependency_binding CHECK (
        (binding='PREPARATION_REFERENCE' AND domain='PREPARATION' AND task_id IS NULL)
        OR (binding='FACT_ADOPTION' AND domain='PREPARATION' AND task_id IS NOT NULL)
        OR (binding='QUESTION_ADOPTION' AND domain='QUESTION' AND task_id IS NOT NULL)
        OR (binding='RELATION_ADOPTION' AND domain='RELATION' AND task_id IS NOT NULL)
        OR (binding='RELATION_EVIDENCE' AND domain='RELATION' AND task_id IS NULL)
        OR (binding='REVIEW_EVIDENCE' AND domain='STANDARD_REVIEW' AND task_id IS NULL)),
    CONSTRAINT ck_material_dependency_target CHECK (
        (domain IN ('PREPARATION','QUESTION') AND target_version_id IS NOT NULL
            AND target_version_number IS NOT NULL AND target_version_number>=1 AND target_revision IS NULL)
        OR (domain='RELATION' AND target_version_id IS NULL AND target_version_number IS NULL AND target_revision IS NULL)
        OR (domain='STANDARD_REVIEW' AND target_version_id IS NULL AND target_version_number IS NULL
            AND target_revision IS NOT NULL AND target_revision BETWEEN 0 AND 9007199254740991)),
    CONSTRAINT ck_material_dependency_title CHECK (CHAR_LENGTH(TRIM(target_title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_material_dependency_resolution CHECK (
        (revision=0 AND choice IS NULL AND reason IS NULL AND resolution_target_version_id IS NULL
            AND resolution_target_revision IS NULL AND decided_at IS NULL)
        OR (revision=1 AND choice IS NOT NULL AND reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 1000
            AND decided_at IS NOT NULL AND decided_at>=created_at AND (
            (domain IN ('PREPARATION','QUESTION') AND choice IN ('KEEP_CURRENT','UPDATED','ARCHIVED')
                AND resolution_target_version_id IS NOT NULL AND resolution_target_revision IS NOT NULL
                AND resolution_target_revision BETWEEN 0 AND 9007199254740991)
            OR (domain='RELATION' AND choice IN ('KEEP_CURRENT','REMOVED')
                AND resolution_target_version_id IS NULL AND resolution_target_revision IS NULL)
            OR (domain='STANDARD_REVIEW' AND choice IN ('KEEP_CURRENT','UPDATED','WITHDRAWN')
                AND resolution_target_version_id IS NULL AND resolution_target_revision IS NOT NULL
                AND resolution_target_revision BETWEEN 0 AND 9007199254740991)))),
    CONSTRAINT ck_material_dependency_time CHECK (
        created_at BETWEEN '1000-01-01 00:00:00.000000' AND '9999-12-31 23:59:59.999999'
        AND (decided_at IS NULL OR decided_at BETWEEN '1000-01-01 00:00:00.000000' AND '9999-12-31 23:59:59.999999'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
