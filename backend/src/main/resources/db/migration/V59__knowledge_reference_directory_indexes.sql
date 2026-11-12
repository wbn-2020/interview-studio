-- 来源与确切知识版本先收窄，再按目标版本游标读取；执行计划和规模表现仍由服务器验收。
CREATE INDEX ix_preparation_knowledge_directory
    ON preparation_reference (owner_id,knowledge_source_item_id,knowledge_source_version_id,target_version_id);

CREATE INDEX ix_question_knowledge_directory
    ON question_knowledge_reference (owner_id,knowledge_item_id,knowledge_version_id,version_id);

CREATE INDEX ix_memory_source_directory
    ON memory_card_source (owner_id,knowledge_item_id,knowledge_version_id,version_id);

CREATE INDEX ix_memory_concept_directory
    ON memory_card_concept (owner_id,concept_id,version_id);
