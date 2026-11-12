-- 服务器显式迁移；最近访问仅保存稳定导航身份，不复制标题或业务内容。
CREATE TABLE recent_visit (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    visited_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id,target_kind,target_id),
    KEY ix_recent_owner_time_kind_id (owner_id,visited_at DESC,target_kind ASC,target_id ASC),
    CONSTRAINT fk_recent_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_recent_kind CHECK (target_kind IN (
        'KNOWLEDGE','PREPARATION','MATERIAL','QUESTION','COLLECTION','TRAINING','RECOLLECTION',
        'MEMORY_CARD','AI_TASK','BOARD','INTERVIEW','PREPARATION_TASK','REPRACTICE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 原知识访问身份与时间原样接续；迁移不改变知识修订、学习或曝光状态。
INSERT INTO recent_visit (owner_id,target_kind,target_id,visited_at)
SELECT owner_id,'KNOWLEDGE',item_id,visited_at FROM knowledge_organization WHERE visited_at IS NOT NULL;
