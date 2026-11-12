-- 按确切知识版本定位审阅证据，再依审阅身份与修订去重分页；保留原索引供既有查询使用。
CREATE INDEX ix_standard_evidence_knowledge_version
    ON standard_review_evidence (owner_id,basis_kind,knowledge_item_id,knowledge_version_id,review_id,revision);
