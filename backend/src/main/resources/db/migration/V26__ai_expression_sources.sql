-- 服务器显式升级；表达复用准备版本关系和原有 owner 复合外键，V20 历史迁移保持原文。
ALTER TABLE ai_preparation_source
    DROP CHECK ck_ai_preparation_source_kind,
    ADD CONSTRAINT ck_ai_preparation_source_kind_v26 CHECK (source_kind IN ('TARGET','PROJECT','FACT','EXPRESSION'));
