-- 目标复用题单／路线的确切版本；组织引用不授予训练参考曝光或模型外发权限。
ALTER TABLE preparation_reference
    DROP CHECK ck_preparation_reference_source,
    ADD COLUMN collection_source_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER knowledge_source_version_id,
    ADD COLUMN collection_source_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER collection_source_item_id,
    ADD UNIQUE KEY uk_preparation_reference_collection
        (target_version_id,source_kind,collection_source_item_id,collection_source_version_id),
    ADD KEY ix_preparation_collection_source
        (owner_id,collection_source_item_id,collection_source_version_id,target_version_id),
    ADD CONSTRAINT fk_preparation_reference_collection
        FOREIGN KEY (owner_id,collection_source_item_id,collection_source_version_id)
        REFERENCES question_collection_version (owner_id,collection_id,id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT ck_preparation_reference_source CHECK (
      (source_kind IN ('PROJECT','FACT')
        AND preparation_source_item_id IS NOT NULL AND preparation_source_version_id IS NOT NULL
        AND knowledge_source_item_id IS NULL AND knowledge_source_version_id IS NULL
        AND collection_source_item_id IS NULL AND collection_source_version_id IS NULL)
      OR (source_kind='KNOWLEDGE'
        AND preparation_source_item_id IS NULL AND preparation_source_version_id IS NULL
        AND knowledge_source_item_id IS NOT NULL AND knowledge_source_version_id IS NOT NULL
        AND collection_source_item_id IS NULL AND collection_source_version_id IS NULL)
      OR (source_kind='COLLECTION' AND use_type='ORGANIZATION'
        AND preparation_source_item_id IS NULL AND preparation_source_version_id IS NULL
        AND knowledge_source_item_id IS NULL AND knowledge_source_version_id IS NULL
        AND collection_source_item_id IS NOT NULL AND collection_source_version_id IS NOT NULL));
