-- 与材料摘录只读引用目录配套在服务器显式执行；本机不执行，不回填或改写任何来源记录。
CREATE INDEX ix_material_ref_fact_excerpt
    ON ai_fact_draft_material (owner_id,excerpt_id,task_id);
CREATE INDEX ix_material_ref_relation_excerpt
    ON ai_relation_evidence_material (owner_id,excerpt_id,task_id);
CREATE INDEX ix_material_ref_preparation_version
    ON preparation_material_reference (owner_id,excerpt_id,version_id);
