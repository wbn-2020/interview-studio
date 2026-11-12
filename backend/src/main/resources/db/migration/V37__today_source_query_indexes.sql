-- 仅由服务器发布步骤显式执行；今日读取复用源对象，不创建派生待办或完成状态表。
-- TODO/IN_PROGRESS 的最近活动页与 OPEN 动作页按完整键集分页；现有 owner,status,id 索引不覆盖该顺序。
CREATE INDEX ix_preparation_task_today_activity
    ON preparation_task (owner_id,status,updated_at DESC,id ASC);

CREATE INDEX ix_review_action_today_activity
    ON review_action (owner_id,status,updated_at DESC,id ASC);
