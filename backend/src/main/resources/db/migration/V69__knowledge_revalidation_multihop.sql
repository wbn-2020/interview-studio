-- 与多跳传播后端、新路径详情接口和前端配套在服务器显式发布；本机不执行。
-- MySQL DDL 隐式提交；部署前备份，部分失败须逐句核查已生效的列／约束后按恢复流程处理。
-- 旧事件只补齐原一跳身份，不追加后代，也不改写决定、幂等回执或任何历史 JSON。
ALTER TABLE knowledge_revalidation_impact
    ADD COLUMN source_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER root_item_id,
    ADD COLUMN parent_impact_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN depth INT NOT NULL DEFAULT 1,
    ADD COLUMN propagation_pending BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE knowledge_revalidation_impact
SET source_item_id=root_item_id
WHERE source_item_id IS NULL;

ALTER TABLE knowledge_revalidation_impact
    DROP FOREIGN KEY fk_knowledge_revalidation_source_path,
    DROP FOREIGN KEY fk_knowledge_revalidation_source_version,
    DROP INDEX uk_knowledge_revalidation_path,
    MODIFY COLUMN source_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD UNIQUE KEY uk_knowledge_revalidation_path (owner_id,event_id,task_id,source_item_id,source_version_id),
    ADD UNIQUE KEY uk_knowledge_revalidation_parent (owner_id,event_id,id,target_item_id),
    ADD KEY ix_knowledge_revalidation_expand
        (owner_id,event_id,propagation_pending,depth,task_id,source_item_id,source_version_id,id);

-- 开启外键检查时新增外键使用 COPY；删旧与建新必须分句，自引用的目标唯一索引先建立。
ALTER TABLE knowledge_revalidation_impact
    ADD CONSTRAINT fk_knowledge_revalidation_source_path FOREIGN KEY (owner_id,task_id,source_item_id,source_version_id)
        REFERENCES ai_knowledge_source (owner_id,task_id,source_item_id,source_version_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_knowledge_revalidation_source_version FOREIGN KEY (owner_id,source_item_id,source_version_id)
        REFERENCES knowledge_version (owner_id,item_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_knowledge_revalidation_parent FOREIGN KEY (owner_id,event_id,parent_impact_id,source_item_id)
        REFERENCES knowledge_revalidation_impact (owner_id,event_id,id,target_item_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT ck_knowledge_revalidation_lineage CHECK (
        (depth=1 AND parent_impact_id IS NULL AND source_item_id=root_item_id)
        OR (depth BETWEEN 2 AND 2147483647 AND parent_impact_id IS NOT NULL AND parent_impact_id<>id)),
    ADD CONSTRAINT ck_knowledge_revalidation_expand CHECK (propagation_pending IN (0,1));

-- 单个实际来源内按任务游标分页；版本范围仍联查真实 knowledge_version，不使用冗余版本号。
ALTER TABLE ai_knowledge_source
    ADD KEY ix_ai_source_revalidation_expand (owner_id,source_item_id,task_id,source_version_id);
