package com.wenjie.studio.dataprivacy;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

import java.util.List;
import java.util.Map;

@Mapper
public interface PrivacyContentMapper {
    record Column(String tableName, String columnName, String dataType, Long maximumLength,
                  boolean primaryKey, boolean generated) { }

    @Select("""
            SELECT TABLE_NAME AS table_name,COLUMN_NAME AS column_name,DATA_TYPE AS data_type,
              CHARACTER_MAXIMUM_LENGTH AS maximum_length,COLUMN_KEY='PRI' AS primary_key,
              EXTRA LIKE '%GENERATED%' AS `generated`
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN
              (SELECT TABLE_NAME FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME='owner_id')
               OR TABLE_SCHEMA=DATABASE() AND TABLE_NAME='knowledge_draft'
            ORDER BY TABLE_NAME,ORDINAL_POSITION
            """)
    List<Column> columns();

    @SelectProvider(type = Sql.class, method = "find")
    List<Map<String, Object>> rows(@Param("owner") String owner, @Param("table") String table,
                                   @Param("columns") List<Column> columns, @Param("ids") List<String> ids,
                                   @Param("limit") int limit);

    @SelectProvider(type = Sql.class, method = "one")
    Map<String, Object> one(@Param("owner") String owner, @Param("table") String table,
                            @Param("idColumn") String idColumn, @Param("id") String id);

    @SelectProvider(type = Sql.class, method = "keyed")
    Map<String, Object> keyed(@Param("owner") String owner, @Param("table") String table,
                              @Param("keys") List<String> keys, @Param("keyValues") Map<String, Object> keyValues);

    @UpdateProvider(type = Sql.class, method = "update")
    int update(@Param("owner") String owner, @Param("table") String table,
               @Param("keys") List<String> keys, @Param("keyValues") Map<String, Object> keyValues,
               @Param("fields") List<String> fields, @Param("values") Map<String, Object> values);

    @UpdateProvider(type = Sql.class, method = "update")
    int updateLocated(@Param("owner") String owner, @Param("table") String table,
                      @Param("keys") List<String> keys, @Param("keyValues") Map<String, Object> keyValues,
                      @Param("fields") List<String> fields, @Param("values") Map<String, Object> values,
                      @Param("hashedLocators") boolean hashedLocators);

    final class Sql {
        private Sql() { }
        @SuppressWarnings("unchecked")
        public static String find(Map<String, Object> args) {
            String tableName = (String) args.get("table");
            String table = identifier(tableName);
            List<Column> columns = (List<Column>) args.get("columns");
            List<String> ids = (List<String>) args.get("ids");
            if (columns == null || columns.isEmpty() || ids == null || ids.isEmpty() || ids.size() > 1_000) {
                throw new IllegalArgumentException("隐私扫描范围无效");
            }
            var predicates = new java.util.ArrayList<String>();
            for (Column column : columns) {
                if ("owner_id".equals(column.columnName()) || column.generated()) continue;
                String name = identifier(column.columnName());
                if ("char".equals(column.dataType()) && Long.valueOf(36).equals(column.maximumLength())) {
                    String values = java.util.stream.IntStream.range(0, ids.size())
                            .mapToObj(index -> "#{ids[" + index + "]}").collect(java.util.stream.Collectors.joining(","));
                    predicates.add(name + " IN (" + values + ")");
                } else if (text(column.dataType())) {
                    for (int index = 0; index < ids.size(); index++) {
                        predicates.add("INSTR(CAST(" + name + " AS CHAR),#{ids[" + index + "]})>0");
                    }
                }
            }
            if (predicates.isEmpty()) return "SELECT * FROM " + table + " WHERE 1=0";
            return "SELECT * FROM " + table + " WHERE " + ownerPredicate(tableName) + " AND ("
                    + String.join(" OR ", predicates) + ") LIMIT #{limit}";
        }
        public static String one(Map<String, Object> args) {
            String table = (String) args.get("table");
            return "SELECT * FROM " + identifier(table)
                    + " WHERE " + ownerPredicate(table) + " AND " + identifier((String) args.get("idColumn")) + "=#{id}";
        }
        @SuppressWarnings("unchecked")
        public static String keyed(Map<String, Object> args) {
            List<String> keys = (List<String>) args.get("keys");
            if (keys == null || keys.isEmpty()) throw new IllegalArgumentException("隐私内容定位不能为空");
            Map<String, Object> values = (Map<String, Object>) args.get("keyValues");
            String where = keys.stream().map(key -> keyPredicate(key, values.get(key), true))
                    .collect(java.util.stream.Collectors.joining(" AND "));
            String table = (String) args.get("table");
            return "SELECT * FROM " + identifier(table)
                    + " WHERE " + ownerPredicate(table) + " AND " + where;
        }
        @SuppressWarnings("unchecked")
        public static String update(Map<String, Object> args) {
            List<String> keys = (List<String>) args.get("keys");
            List<String> fields = (List<String>) args.get("fields");
            if (keys == null || keys.isEmpty() || fields == null || fields.isEmpty()) {
                throw new IllegalArgumentException("隐私清除定位无效");
            }
            String set = fields.stream().map(field -> identifier(field) + "=#{values." + field + "}")
                    .collect(java.util.stream.Collectors.joining(","));
            Map<String, Object> keyValues = (Map<String, Object>) args.get("keyValues");
            String where = keys.stream().map(key -> keyPredicate(key, keyValues.get(key),
                            args.containsKey("hashedLocators") && Boolean.TRUE.equals(args.get("hashedLocators"))))
                    .collect(java.util.stream.Collectors.joining(" AND "));
            String table = (String) args.get("table");
            return "UPDATE " + identifier(table) + " SET " + set
                    + " WHERE " + ownerPredicate(table) + " AND " + where;
        }
        static String ownerPredicate(String table) {
            identifier(table);
            // 初始草稿表的归属只由主知识项确定，不能漏扫，也不能把外部 owner 参数当成草稿列。
            return "knowledge_draft".equals(table)
                    ? "EXISTS (SELECT 1 FROM knowledge_item privacy_owner WHERE privacy_owner.id="
                    + identifier(table) + ".item_id AND privacy_owner.owner_id=#{owner})"
                    : "owner_id=#{owner}";
        }
        private static String keyPredicate(String key, Object value, boolean hashedLocators) {
            String name = identifier(key);
            return hashedLocators && value instanceof String text && text.matches("sha256:[0-9a-f]{64}")
                    ? "SHA2(CAST(" + name + " AS CHAR),256)=SUBSTRING(#{keyValues." + key + "},8)"
                    : name + "=#{keyValues." + key + "}";
        }
        static boolean text(String type) {
            return SetHolder.TEXT.contains(type);
        }
        static String identifier(String name) {
            if (name == null || !name.matches("[a-z][a-z0-9_]{0,63}")) {
                throw new IllegalArgumentException("隐私存储标识符无效");
            }
            return "`" + name + "`";
        }
        private static final class SetHolder {
            private static final java.util.Set<String> TEXT = java.util.Set.of("varchar", "text",
                    "tinytext", "mediumtext", "longtext", "json");
        }
    }
}
