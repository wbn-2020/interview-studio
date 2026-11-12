-- 可用性往返变化仍使旧授权失效；收藏等整理变更只增加既有实体修订。
ALTER TABLE preparation_item
    ADD COLUMN source_generation BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_preparation_source_generation CHECK (source_generation BETWEEN 0 AND revision);
