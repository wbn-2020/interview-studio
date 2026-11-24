-- 由服务器发布步骤显式执行；应用启动不自动迁移，不在本机运行本文件。
ALTER TABLE command_receipt ADD COLUMN expires_at DATETIME(6) NULL;
UPDATE command_receipt SET expires_at = DATE_ADD(created_at, INTERVAL 30 DAY) WHERE expires_at IS NULL;
ALTER TABLE command_receipt MODIFY COLUMN expires_at DATETIME(6) NOT NULL;
CREATE INDEX ix_receipt_owner_expiry ON command_receipt (owner_id, expires_at);
