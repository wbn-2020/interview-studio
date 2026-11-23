import org.flywaydb.core.Flyway;

/** 独立迁移入口：仅迁移专属库，不依赖应用启动或隐式自动配置。 */
public final class Migrate {
    public static void main(String[] args) {
        String url = required("STUDIO_DATABASE_URL");
        String username = required("STUDIO_DATABASE_USERNAME");
        String password = required("STUDIO_DATABASE_PASSWORD");
        // 禁止 clean／baseline，非空无账本的库必须人工检查，不能猜测既有版本。
        var result = Flyway.configure()
                .dataSource(url, username, password)
                .locations("filesystem:/release/migration/sql")
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load()
                .migrate();
        System.out.println("MIGRATION_COMPLETED executed=" + result.migrationsExecuted
                + " targetVersion=" + result.targetSchemaVersion);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing migration setting: " + name);
        }
        return value;
    }
}
