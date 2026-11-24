-- 由服务器发布步骤显式执行；应用启动不自动迁移，不在本机运行本文件。
-- 一个 owner 仅一条 Web 运行时配置；密钥使用 AES-GCM，主密钥只存在于 STUDIO_AI_CONFIG_KEY。
CREATE TABLE ai_runtime_config (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    protocol VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider VARCHAR(120) NOT NULL,
    endpoint VARCHAR(2048) NOT NULL,
    model VARCHAR(200) NOT NULL,
    api_key_ciphertext VARBINARY(8221) NOT NULL,
    config_version VARCHAR(80) NOT NULL,
    price_version VARCHAR(80) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_price_micros_per_million BIGINT NOT NULL,
    output_price_micros_per_million BIGINT NOT NULL,
    daily_budget_micros BIGINT NOT NULL,
    max_output_tokens INT NOT NULL,
    timeout_seconds INT NOT NULL,
    max_tokens_field VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    json_mode BOOLEAN NOT NULL,
    streaming BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id),
    CONSTRAINT fk_ai_runtime_config_owner FOREIGN KEY (owner_id)
        REFERENCES owner_workspace (owner_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ai_runtime_config_revision CHECK (revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_ai_runtime_config_protocol CHECK (protocol IN ('CHAT_COMPLETIONS','RESPONSES')),
    CONSTRAINT ck_ai_runtime_config_text CHECK (
        CHAR_LENGTH(provider) BETWEEN 0 AND 120 AND CHAR_LENGTH(endpoint) BETWEEN 0 AND 2048
        AND CHAR_LENGTH(model) BETWEEN 0 AND 200 AND CHAR_LENGTH(config_version) BETWEEN 0 AND 80
        AND CHAR_LENGTH(price_version) BETWEEN 0 AND 80),
    CONSTRAINT ck_ai_runtime_config_currency CHECK (CHAR_LENGTH(currency) BETWEEN 0 AND 3),
    CONSTRAINT ck_ai_runtime_config_prices CHECK (
        input_price_micros_per_million BETWEEN -1 AND 9007199254740991
        AND output_price_micros_per_million BETWEEN -1 AND 9007199254740991
        AND daily_budget_micros BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_ai_runtime_config_limits CHECK (
        max_output_tokens BETWEEN 0 AND 16384 AND timeout_seconds BETWEEN 0 AND 120),
    CONSTRAINT ck_ai_runtime_config_tokens_field CHECK (
        max_tokens_field IN ('','max_tokens','max_completion_tokens','max_output_tokens')),
    CONSTRAINT ck_ai_runtime_config_ciphertext CHECK (OCTET_LENGTH(api_key_ciphertext) BETWEEN 0 AND 8221)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
