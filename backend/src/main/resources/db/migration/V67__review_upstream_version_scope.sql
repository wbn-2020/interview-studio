-- 与 V66、审阅来源 DTO 和窄成长投影配套在服务器显式发布；本机不执行，不改写历史快照。
-- MySQL DDL 会隐式提交，部署前备份并逐句核验；旧事件不虚构采用范围。
ALTER TABLE review_basis_change
    ADD COLUMN upstream_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT fk_review_basis_upstream_version FOREIGN KEY (owner_id,source_id,upstream_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    DROP CHECK ck_review_basis_change_identity,
    ADD CONSTRAINT ck_review_basis_change_identity CHECK (
        (source_kind='QUESTION' AND upstream_version_id IS NULL
            AND previous_version_id IS NOT NULL AND current_version_id IS NOT NULL AND (
            (change_kind='QUESTION_VERSION_CHANGED' AND previous_version_id<>current_version_id
                AND previous_status='ACTIVE' AND current_status='ACTIVE')
            OR (change_kind='QUESTION_ARCHIVED' AND previous_version_id=current_version_id
                AND previous_status='ACTIVE' AND current_status='ARCHIVED')
            OR (change_kind='QUESTION_RESTORED' AND previous_version_id=current_version_id
                AND previous_status='ARCHIVED' AND current_status='ACTIVE')))
        OR (source_kind='KNOWLEDGE' AND previous_version_id IS NOT NULL AND current_version_id IS NOT NULL AND (
            (upstream_version_id IS NULL AND (
                (change_kind='KNOWLEDGE_BODY_CHANGED' AND previous_version_id<>current_version_id
                    AND previous_status='ACTIVE' AND current_status='ACTIVE')
                OR (change_kind='KNOWLEDGE_ARCHIVED' AND previous_version_id=current_version_id
                    AND previous_status='ACTIVE' AND current_status='ARCHIVED')
                OR (change_kind='KNOWLEDGE_RESTORED' AND previous_version_id=current_version_id
                    AND previous_status='ARCHIVED' AND current_status='ACTIVE')))
            OR (change_kind='KNOWLEDGE_UPSTREAM_CHANGED' AND upstream_version_id IS NOT NULL
                AND previous_version_id=current_version_id AND previous_status=current_status
                AND current_status IN ('ACTIVE','ARCHIVED'))))
        OR (source_kind='MATERIAL_ATTACHMENT' AND change_kind='MATERIAL_BASIS_CHANGED'
            AND upstream_version_id IS NULL AND previous_version_id IS NULL AND current_version_id IS NULL
            AND previous_status IN ('RESERVED','READY') AND current_status IN ('RESERVED','READY')));
