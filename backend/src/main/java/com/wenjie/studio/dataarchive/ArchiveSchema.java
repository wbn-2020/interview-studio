package com.wenjie.studio.dataarchive;

import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static com.wenjie.studio.dataarchive.DataArchiveDtos.*;

final class ArchiveSchema {
    static final Set<String> EXCLUDED_TABLES = Set.of("flyway_schema_history", "data_archive_job", "data_restore_gate",
            "search_projection", "search_generation_event", "spring_session", "spring_session_attributes");
    private static final Pattern TABLE = Pattern.compile("(?im)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([a-z][a-z0-9_]*)`?\\s*\\(");
    private ArchiveSchema() {}

    static String quote(String identifier) {
        if (identifier == null || !identifier.matches("[a-z][a-z0-9_]{0,63}"))
            throw new IllegalArgumentException("数据表或字段标识无效");
        return "`" + identifier + "`";
    }

    static Schema read(Connection connection, JsonMapper json) throws SQLException, IOException {
        String catalog = connection.getCatalog();
        if (catalog == null || catalog.isBlank()) throw new SQLException("ARCHIVE_DATABASE_UNSET");
        Set<String> known = new TreeSet<>();
        for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql")) {
            String sql;
            try (var input = resource.getInputStream()) { sql = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
            var matcher = TABLE.matcher(sql);
            while (matcher.find()) known.add(matcher.group(1));
        }
        if (!known.contains("owner_workspace") || known.size() > 500) throw new IOException("ARCHIVE_SCHEMA_REGISTRY_INVALID");
        Set<String> actual = new TreeSet<>();
        try (var result = connection.getMetaData().getTables(catalog, null, "%", new String[]{"TABLE"})) {
            while (result.next()) actual.add(result.getString("TABLE_NAME"));
        }
        Set<String> unknown = new HashSet<>(actual);
        unknown.removeAll(known);
        unknown.removeAll(EXCLUDED_TABLES);
        if (!unknown.isEmpty() || !actual.containsAll(known))
            throw new SQLException("ARCHIVE_SCHEMA_MIGRATION_MISMATCH");
        actual.removeAll(EXCLUDED_TABLES);
        List<Table> tables = new ArrayList<>();
        for (String name : actual) {
            List<Column> columns = new ArrayList<>();
            var expressions = new TreeMap<String, String>();
            try (var query = connection.prepareStatement("SELECT COLUMN_NAME, GENERATION_EXPRESSION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
                query.setQueryTimeout(5); query.setString(1, catalog); query.setString(2, name);
                try (var result = query.executeQuery()) {
                    while (result.next()) expressions.put(result.getString(1), result.getString(2));
                }
            }
            try (var result = connection.getMetaData().getColumns(catalog, null, name, "%")) {
                while (result.next()) {
                    String column = result.getString("COLUMN_NAME");
                    quote(column);
                    if (Set.of("api_key", "password", "password_hash", "access_token", "refresh_token", "private_key", "client_secret").contains(column))
                        throw new SQLException("ARCHIVE_CREDENTIAL_COLUMN_UNSUPPORTED");
                    String generated = result.getString("IS_GENERATEDCOLUMN");
                    columns.add(new Column(column, result.getInt("DATA_TYPE"), result.getString("TYPE_NAME"),
                            result.getInt("COLUMN_SIZE"), result.getInt("DECIMAL_DIGITS"),
                            result.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNullable,
                            "YES".equals(generated), result.getString("COLUMN_DEF"), expressions.get(column)));
                }
            }
            if (columns.isEmpty() || columns.size() > 100) throw new SQLException("ARCHIVE_COLUMNS_INVALID");
            var primary = new TreeMap<Short, String>();
            try (var result = connection.getMetaData().getPrimaryKeys(catalog, null, name)) {
                while (result.next()) primary.put(result.getShort("KEY_SEQ"), result.getString("COLUMN_NAME"));
            }
            if (primary.isEmpty()) throw new SQLException("ARCHIVE_PRIMARY_KEY_REQUIRED");
            List<ForeignKey> keys = new ArrayList<>();
            try (var result = connection.getMetaData().getImportedKeys(catalog, null, name)) {
                while (result.next()) {
                    if (!catalog.equals(result.getString("PKTABLE_CAT"))) throw new SQLException("ARCHIVE_CROSS_DATABASE_REFERENCE");
                    keys.add(new ForeignKey(result.getString("FK_NAME"), result.getString("PKTABLE_NAME"),
                            result.getInt("KEY_SEQ"), result.getString("FKCOLUMN_NAME"), result.getString("PKCOLUMN_NAME")));
                }
            }
            keys.sort(Comparator.comparing(ForeignKey::name).thenComparingInt(ForeignKey::ordinal));
            String definition;
            try (var query = connection.createStatement()) {
                query.setQueryTimeout(5);
                try (var result = query.executeQuery("SHOW CREATE TABLE " + quote(name))) {
                    if (!result.next()) throw new SQLException("ARCHIVE_DEFINITION_MISSING");
                    definition = result.getString(2).replaceAll(" AUTO_INCREMENT=[0-9]+", "");
                }
            }
            tables.add(new Table(name, List.copyOf(columns), List.copyOf(primary.values()), List.copyOf(keys),
                    ArchivePaths.sha256(definition.getBytes(StandardCharsets.UTF_8))));
        }
        List<Table> sorted = ordered(tables);
        return new Schema(sorted, ArchivePaths.sha256(json.writeValueAsBytes(sorted)));
    }

    static List<Table> ordered(List<Table> tables) throws SQLException {
        List<Table> result = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        while (result.size() != tables.size()) {
            boolean changed = false;
            for (Table table : tables) {
                if (done.contains(table.name())) continue;
                if (table.foreignKeys().stream().allMatch(key -> key.parentTable().equals(table.name()) || done.contains(key.parentTable()))) {
                    result.add(table); done.add(table.name()); changed = true;
                }
            }
            if (!changed) {
                // 追问与原答在表级成环，真实记录仍有先后；恢复按完整行重试外键依赖，不关闭检查。
                for (Table table : tables) if (done.add(table.name())) result.add(table);
            }
        }
        return List.copyOf(result);
    }

    static void ownerOnly(Connection connection, Schema schema, String owner) throws SQLException {
        ArchivePaths.uuid(owner);
        for (Table table : schema.tables()) {
            if (table.columns().stream().noneMatch(column -> column.name().equals("owner_id"))) continue;
            try (var query = connection.prepareStatement("SELECT 1 FROM " + quote(table.name()) + " WHERE owner_id IS NULL OR owner_id<>? LIMIT 1")) {
                query.setQueryTimeout(5); query.setString(1, owner);
                try (var result = query.executeQuery()) {
                    if (result.next()) throw new SQLException("ARCHIVE_OTHER_OWNER_PRESENT");
                }
            }
        }
    }

    static boolean empty(Connection connection, Schema schema) throws SQLException {
        for (Table table : schema.tables()) {
            try (Statement query = connection.createStatement()) {
                query.setQueryTimeout(5);
                try (ResultSet result = query.executeQuery("SELECT 1 FROM " + quote(table.name()) + " LIMIT 1")) {
                    if (result.next()) return false;
                }
            }
        }
        return true;
    }
}
