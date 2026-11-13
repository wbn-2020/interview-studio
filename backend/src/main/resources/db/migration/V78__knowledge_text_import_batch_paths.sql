-- 旧清单仅能回填原文件名；相对路径只作元数据，不映射服务器文件系统。
ALTER TABLE knowledge_text_import_batch_entry
    ADD COLUMN source_path VARCHAR(600) COLLATE utf8mb4_0900_bin NULL;

UPDATE knowledge_text_import_batch_entry SET source_path=file_name;

ALTER TABLE knowledge_text_import_batch_entry
    MODIFY COLUMN source_path VARCHAR(600) COLLATE utf8mb4_0900_bin NOT NULL,
    DROP INDEX uk_text_batch_entry_name,
    ADD UNIQUE KEY uk_text_batch_entry_path (owner_id, batch_id, source_path),
    ADD CONSTRAINT ck_text_batch_entry_path CHECK (CHAR_LENGTH(source_path) BETWEEN 1 AND 600);
