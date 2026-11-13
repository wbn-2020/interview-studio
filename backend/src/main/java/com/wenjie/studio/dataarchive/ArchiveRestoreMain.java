package com.wenjie.studio.dataarchive;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * 通过已发布 JAR 的 PropertiesLauncher 指定此主类运行。只连接显式命名的恢复库，
 * 不调用 StudioApplication、不初始化 Spring、不启动 HTTP／AI／解析服务。
 */
public final class ArchiveRestoreMain {
    private ArchiveRestoreMain() {}

    public static void main(String[] args) {
        try {
            Object result = run(args);
            System.out.println(new JsonMapper().writeValueAsString(result));
        } catch (Exception exception) {
            // JDBC异常和配置值可能含连接信息；命令行只输出固定失败分类。
            String code = exception instanceof java.io.IOException && exception.getMessage() != null
                    && exception.getMessage().matches("[A-Z][A-Z0-9_]{1,79}")
                    ? exception.getMessage() : "RESTORE_COMMAND_FAILED";
            System.err.println(new JsonMapper().writeValueAsString(Map.of("state", "FAILED",
                    "code", code, "noAutomaticUnlock", true)));
            System.exit(2);
        }
    }

    private static Object run(String[] args) throws Exception {
        if (args == null || args.length < 1
                || !Set.of("prepare", "preview", "restore", "finish", "ai-configuration", "confirm-ai").contains(args[0]))
            throw new IllegalArgumentException("恢复子命令无效");
        String command = args[0];
        Map<String, String> flags = new HashMap<>();
        for (int index = 1; index < args.length; index += 2) {
            if (index + 1 >= args.length || !args[index].startsWith("--") || flags.putIfAbsent(args[index].substring(2), args[index + 1]) != null)
                throw new IllegalArgumentException("恢复参数未知或重复");
        }
        Set<String> expected = switch (command) {
            case "ai-configuration" -> Set.of();
            case "confirm-ai" -> Set.of("owner", "database", "latest-sequence", "latest-sha256",
                    "configuration-fingerprint", "confirm-new-authorizations", "confirm-restored-environment");
            case "prepare" -> Set.of("owner", "database", "confirm-empty-environment");
            case "preview" -> Set.of("owner", "database", "archive", "latest-sequence", "latest-sha256");
            case "restore" -> Set.of("owner", "database", "archive", "archive-sha256", "materials", "indexes",
                    "latest-sequence", "latest-sha256", "confirm-empty-environment");
            default -> Set.of("owner", "database", "materials", "indexes", "latest-sequence", "latest-sha256", "confirm-sealed-environment");
        };
        if (!flags.keySet().equals(expected) || flags.values().stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("恢复参数不完整");
        if (!"true".equals(System.getenv("STUDIO_RESTORE_ENVIRONMENT"))) throw new IllegalStateException("必须显式声明恢复环境");
        if (command.equals("ai-configuration")) return ArchiveAiConfirmation.publicConfiguration();
        if (flags.containsKey("confirm-empty-environment") && !flags.get("database").equals(flags.get("confirm-empty-environment")))
            throw new IllegalArgumentException("空环境确认须等于目标数据库名");
        if (flags.containsKey("confirm-sealed-environment") && !flags.get("database").equals(flags.get("confirm-sealed-environment")))
            throw new IllegalArgumentException("封闭环境确认须等于目标数据库名");
        if (flags.containsKey("confirm-restored-environment") && (!flags.get("database").equals(flags.get("confirm-restored-environment"))
                || !"required".equals(flags.get("confirm-new-authorizations"))))
            throw new IllegalArgumentException("重新启用模型须确认恢复库名与重新授权");
        String url = requiredEnvironment("STUDIO_RESTORE_DATABASE_URL");
        if (!url.startsWith("jdbc:mysql://") || url.matches("(?is).*([?&;](?:password|user|allowMultiQueries)=).*"))
            throw new IllegalArgumentException("恢复数据库连接须为不含凭据及多语句参数的MySQL URL");
        JsonMapper json = new JsonMapper();
        var providers = ServiceLoader.load(ArchiveLifecyclePolicy.class).stream().toList();
        if (providers.size() != 1) throw new IllegalStateException("离线隐私账本恢复实现未唯一配置");
        ArchiveLifecyclePolicy policy = providers.getFirst().get();
        var engine = new ArchiveRestoreEngine(json, policy, 4_294_967_296L, 2_000_000);
        try (var target = DriverManager.getConnection(url, requiredEnvironment("STUDIO_RESTORE_DATABASE_USERNAME"),
                requiredEnvironment("STUDIO_RESTORE_DATABASE_PASSWORD"))) {
            target.setAutoCommit(false); target.setTransactionIsolation(java.sql.Connection.TRANSACTION_SERIALIZABLE);
            try (var query = target.createStatement()) { query.setQueryTimeout(5); query.execute("SET time_zone = '+00:00'"); }
            String database = flags.get("database"), owner = flags.get("owner");
            if (!database.matches("[a-zA-Z0-9_]{1,64}") || !database.equals(target.getCatalog()))
                throw new IllegalArgumentException("目标数据库确认不匹配");
            String lock = "wenjie-restore-" + ArchivePaths.sha256(database.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 32);
            try (var query = target.prepareStatement("SELECT GET_LOCK(?,0)")) {
                query.setQueryTimeout(5); query.setString(1, lock);
                try (var result = query.executeQuery()) { if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException("已有恢复命令正在操作此环境"); }
            }
            try {
                if (command.equals("prepare")) {
                    engine.prepare(target, database, owner);
                    return Map.of("state", "SEALED", "ownerId", owner, "targetDatabase", database);
                }
                var latest = new ArchiveLifecyclePolicy.Checkpoint(Long.parseLong(flags.get("latest-sequence")), flags.get("latest-sha256"));
                if (command.equals("confirm-ai"))
                    return ArchiveAiConfirmation.confirm(target, policy, owner, latest, flags.get("configuration-fingerprint"));
                if (command.equals("preview")) return engine.preview(target, database, owner, absolute(flags.get("archive")), latest);
                if (command.equals("restore")) return engine.restore(target, database, owner, absolute(flags.get("archive")),
                        flags.get("archive-sha256"), absolute(flags.get("materials")), absolute(flags.get("indexes")), latest);
                engine.finish(target, database, owner, absolute(flags.get("materials")), absolute(flags.get("indexes")), latest);
                return Map.of("state", "OPEN_AI_BLOCKED", "ownerId", owner, "latestCheckpoint", latest);
            } finally {
                target.rollback();
                try (var query = target.prepareStatement("SELECT RELEASE_LOCK(?)")) { query.setQueryTimeout(5); query.setString(1, lock); query.execute(); }
            }
        }
    }

    private static Path absolute(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute() || !path.equals(path.normalize())) throw new IllegalArgumentException("恢复文件目录须为规范化绝对路径");
        return path;
    }

    private static String requiredEnvironment(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("恢复环境配置缺失");
        return value;
    }
}
