package com.wenjie.studio.dataarchive;

import com.wenjie.studio.ai.AiProperties;

import java.io.IOException;
import java.sql.Connection;
import java.util.Map;

/** 恢复后的人工配置确认；只读取公开配置，密钥可用性仍由应用实际调用验证。 */
final class ArchiveAiConfirmation {
    private ArchiveAiConfirmation() { }

    static Map<String, Object> publicConfiguration() {
        var properties = publicProperties();
        return Map.of("state", "PUBLIC_CONFIGURATION_ONLY", "configuration", properties.configuration(),
                "configurationFingerprint", properties.fingerprint(), "credentialsChecked", false);
    }

    static Map<String, Object> confirm(Connection target, ArchiveLifecyclePolicy policy, String owner,
                                       ArchiveLifecyclePolicy.Checkpoint latest, String fingerprint) throws Exception {
        ArchivePaths.uuid(owner);
        var configuration = publicProperties();
        configuration.requireReady();
        if (!configuration.fingerprint().equals(fingerprint)) throw new IOException("RESTORE_AI_CONFIGURATION_CHANGED");
        try (var query = target.prepareStatement("SELECT owner_id FROM owner_workspace WHERE owner_id=? FOR UPDATE")) {
            query.setQueryTimeout(10); query.setString(1, owner);
            try (var result = query.executeQuery()) { if (!result.next()) throw new IOException("RESTORE_OWNER_NOT_FOUND"); }
        }
        policy.verifyExportCheckpoint(target, owner, latest);
        try (var query = target.prepareStatement("SELECT 1 FROM data_restore_gate WHERE singleton_id=1 AND owner_id=? AND state='OPEN' AND archive_id IS NOT NULL FOR UPDATE")) {
            query.setQueryTimeout(5); query.setString(1, owner);
            try (var result = query.executeQuery()) { if (!result.next()) throw new IOException("RESTORE_AI_CONFIRMATION_NOT_ALLOWED"); }
        }
        for (String sql : new String[]{
                "SELECT 1 FROM ai_scope_authorization WHERE owner_id=? AND state='ACTIVE' LIMIT 1",
                "SELECT 1 FROM ai_task WHERE owner_id=? AND status IN ('QUEUED','RUNNING','VALIDATING','CANCEL_REQUESTED') LIMIT 1",
                "SELECT 1 FROM ai_attempt WHERE owner_id=? AND status IN ('QUEUED','RUNNING','VALIDATING','CANCEL_REQUESTED') LIMIT 1"}) {
            try (var query = target.prepareStatement(sql)) {
                query.setQueryTimeout(5); query.setString(1, owner);
                try (var result = query.executeQuery()) {
                    if (result.next()) throw new IOException("RESTORE_AI_RECONFIRMATION_REQUIRES_REVOKED_GRANTS_AND_IDLE_TASKS");
                }
            }
        }
        if (!latest.equals(policy.current(owner))) throw new IOException("RESTORE_AI_LEDGER_CHANGED");
        try (var update = target.prepareStatement("UPDATE data_restore_gate SET ai_blocked=FALSE,"
                + "ack_ai_config_fingerprint=?,ack_ai_config_version=?,ack_ai_config_at=UTC_TIMESTAMP(6),"
                + "ledger_sequence=?,ledger_sha256=?,updated_at=UTC_TIMESTAMP(6)"
                + " WHERE singleton_id=1 AND owner_id=? AND state='OPEN' AND archive_id IS NOT NULL")) {
            update.setQueryTimeout(5); update.setString(1, fingerprint);
            update.setString(2, configuration.configuration().configVersion());
            update.setLong(3, latest.sequence()); update.setString(4, latest.sha256()); update.setString(5, owner);
            if (update.executeUpdate() != 1) throw new IOException("RESTORE_AI_CONFIRMATION_LOST");
        }
        target.commit();
        return Map.of("state", "OPEN_AI_CONFIGURATION_CONFIRMED", "ownerId", owner,
                "configurationFingerprint", fingerprint, "newAuthorizationsRequired", true,
                "credentialsChecked", false);
    }

    private static AiProperties publicProperties() {
        // configured 字段参与公开指纹；固定占位仅用于验证公开参数，绝不读取或记录真实密钥。
        return new AiProperties(bool("STUDIO_AI_ENABLED", false), env("STUDIO_AI_PROTOCOL", "CHAT_COMPLETIONS"),
                env("STUDIO_AI_PROVIDER", ""), env("STUDIO_AI_ENDPOINT", ""),
                env("STUDIO_AI_MODEL", ""), "offline-public-configuration",
                env("STUDIO_AI_CONFIG_VERSION", ""), env("STUDIO_AI_PRICE_VERSION", ""),
                env("STUDIO_AI_CURRENCY", ""), number("STUDIO_AI_INPUT_PRICE_MICROS_PER_MILLION", -1),
                number("STUDIO_AI_OUTPUT_PRICE_MICROS_PER_MILLION", -1), number("STUDIO_AI_DAILY_BUDGET_MICROS", 0),
                Math.toIntExact(number("STUDIO_AI_MAX_OUTPUT_TOKENS", 1024)),
                Math.toIntExact(number("STUDIO_AI_TIMEOUT_SECONDS", 60)), env("STUDIO_AI_MAX_TOKENS_FIELD", "max_tokens"),
                bool("STUDIO_AI_JSON_MODE", false), bool("STUDIO_AI_STREAMING", false));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private static boolean bool(String name, boolean fallback) {
        String value = env(name, Boolean.toString(fallback));
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
            throw new IllegalArgumentException("模型公开配置中的布尔参数无效");
        return Boolean.parseBoolean(value);
    }

    private static long number(String name, long fallback) { return Long.parseLong(env(name, Long.toString(fallback))); }
}
