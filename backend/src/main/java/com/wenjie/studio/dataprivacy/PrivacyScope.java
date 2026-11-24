package com.wenjie.studio.dataprivacy;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.ai.AiManifestDigest;
import com.wenjie.studio.memory.MemoryDtos;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.temporal.TemporalAccessor;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static com.wenjie.studio.dataprivacy.DataPrivacyDtos.*;

/**
 * 以登记身份追踪副本，不把普通结构链接当作删除独立笔记的依据。
 * 派生混合正文必须整份列入确认；白板／答案中的结构引用只清对应快照。
 */
@Component
public class PrivacyScope {
    /** Web 对象选择只开放本契约列出的回收站身份；白板、AI 任务与用途授权仍走各自管理入口。 */
    public enum PrivacyActionableKind {
        KNOWLEDGE, PREPARATION, QUESTION, MEMORY_CARD, QUESTION_COLLECTION, RECOLLECTION,
        TRAINING, REVIEW_FEEDBACK, REVIEW_ACTION, PREPARATION_TASK, INTERVIEW_EVENT, MATERIAL
    }

    private static final int MAX_IDENTITIES = 1_000;
    private static final int MAX_ROWS = 20_000;
    private static final int MAX_ROWS_PER_TABLE = 5_000;
    private static final long MAX_CONTENT_BYTES = 67_108_864;
    private static final String MARKER = "已清除";
    private static final Set<String> SKIP_TABLES = Set.of("owner_workspace", "privacy_object_state",
            "privacy_ledger_checkpoint", "search_generation_event", "ai_event_cursor");
    private static final Set<String> CONTENT = Set.of("title", "source_title", "target_title", "root_title", "body", "text", "excerpt",
            "description", "file_name", "reason", "note", "notes", "summary", "definition",
            "reference_text", "prompt", "answer", "question_text", "reconciliation_note",
            "locator", "source_url", "url", "author", "license_note", "copyright_note",
            "rationale", "explanation", "label", "name", "term", "display_name", "query",
            "reference_answer", "scenario", "stage", "detail", "checkpoint", "author_label",
            "verification", "tracking_reason", "change_reason", "round_name", "location", "meeting_url");
    private static final Set<String> KEEP_JSON_KEYS = Set.of("id", "kind", "type", "status", "mode",
            "provider", "model", "currency", "format", "schemaVersion", "formatVersion",
            "baselineKind", "sourceType", "sourceKind", "targetKind", "referenceKind",
            "evidenceKind", "basisKind", "relationType", "algorithm", "algorithmVersion", "rating",
            "state", "role", "decision", "action", "visibility", "provenance", "contentType");
    private static final Map<String, Spec> SPECS = Map.ofEntries(
            Map.entry("KNOWLEDGE", new Spec("knowledge_item", "id", "metadata_revision", "status")),
            Map.entry("PREPARATION", new Spec("preparation_item", "id", "revision", "status")),
            Map.entry("QUESTION", new Spec("question", "id", "revision", "status")),
            Map.entry("MEMORY_CARD", new Spec("memory_card", "id", "revision", "status")),
            Map.entry("BOARD", new Spec("knowledge_board", "id", "revision", null)),
            Map.entry("MATERIAL", new Spec("material_attachment", "id", "revision", "status")),
            Map.entry("AI_TASK", new Spec("ai_task", "id", "revision", "status")),
            Map.entry("TRAINING", new Spec("training_session", "id", "revision", "status")),
            Map.entry("REVIEW_FEEDBACK", new Spec("review_feedback", "id", "revision", null)),
            Map.entry("RECOLLECTION", new Spec("review_recollection", "id", "revision", "status")),
            Map.entry("PREPARATION_TASK", new Spec("preparation_task", "id", "revision", "status")),
            Map.entry("REVIEW_ACTION", new Spec("review_action", "id", "revision", "status")),
            Map.entry("QUESTION_COLLECTION", new Spec("question_collection", "id", "revision", "status")),
            Map.entry("INTERVIEW_EVENT", new Spec("interview_event", "id", "revision", "status")),
            Map.entry("AI_AUTHORIZATION", new Spec("ai_scope_authorization", "id", "revision", "state")));

    private final PrivacyContentMapper mapper;
    private final JsonMapper json;

    public PrivacyScope(PrivacyContentMapper mapper, JsonMapper json) {
        this.mapper = mapper;
        this.json = json.rebuild().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    }

    record Spec(String table, String idColumn, String revisionColumn, String statusColumn) { }
    record Row(String table, Map<String, Object> values, List<String> keys) { }
    public record Change(String table, Map<String, Object> keys, Map<String, Object> values) { }
    public record Plan(Preview preview, List<PrivacyLedger.Subject> subjects,
                       List<PrivacyLedger.Redaction> redactions, List<Change> changes) { }

    public ObjectInfo object(String owner, String kind, String id) {
        Spec spec = spec(kind);
        Map<String, Object> row = normalize(mapper.one(owner, spec.table(), spec.idColumn(), id));
        if (row == null) throw new ApiException(404, "PRIVACY_OBJECT_NOT_FOUND", "内容不存在或不属于当前工作区。");
        String title = string(row, "title");
        if (title == null && "KNOWLEDGE".equals(kind)) {
            var version = normalize(mapper.one(owner, "knowledge_version", "id", string(row, "current_version_id")));
            title = version == null ? null : string(version, "title");
        }
        if (title == null) title = string(row, "file_name");
        if (title == null) title = kind + " · " + id;
        String status = spec.statusColumn() == null ? "AVAILABLE" : string(row, spec.statusColumn());
        long revision = number(row.get(spec.revisionColumn()));
        return new ObjectInfo(kind, id, title, status == null ? "AVAILABLE" : status, revision, "SELECTED_OBJECT");
    }

    public Preview simplePreview(String owner, String action, String kind, String id) {
        var object = object(owner, kind, id);
        String digest = PrivacyLedger.digest(json.writeValueAsString(List.of(action, object)));
        return new Preview(action, kind, id, digest, List.of(object), List.of(),
                List.of("回收站保留正文与关联身份；恢复只解除访问屏蔽，已有归档、复习暂停和模型用途不会自动重新启用。",
                        "保留 30 天只是处理提示，不会自动彻底清除。"), 0);
    }

    public Plan purgePreview(String owner, String kind, String id) {
        object(owner, kind, id);
        Map<String, List<PrivacyContentMapper.Column>> schema = schema();
        Map<String, LinkedHashSet<String>> roots = new TreeMap<>();
        add(roots, kind, id);
        Set<String> identities = new LinkedHashSet<>();
        identities.add(id);
        Map<String, Row> rows = new TreeMap<>();
        Set<String> scannedIdentities = new LinkedHashSet<>();
        long contentBytes = 0;
        for (int wave = 0; wave < 64; wave++) {
            List<String> next = identities.stream().filter(value -> !scannedIdentities.contains(value)).toList();
            if (next.isEmpty()) break;
            if (identities.size() > MAX_IDENTITIES) throw capacity();
            scannedIdentities.addAll(next);
            for (var entry : schema.entrySet()) {
                List<PrivacyContentMapper.Column> columns = entry.getValue();
                for (int offset = 0; offset < next.size(); offset += 25) {
                    var batch = next.subList(offset, Math.min(next.size(), offset + 25));
                    List<Map<String, Object>> found = mapper.rows(owner, entry.getKey(), columns, batch, MAX_ROWS_PER_TABLE + 1);
                    if (found.size() > MAX_ROWS_PER_TABLE) throw capacity();
                    for (var raw : found) {
                        Map<String, Object> value = normalize(raw);
                        List<String> keys = columns.stream().filter(PrivacyContentMapper.Column::primaryKey)
                                .map(PrivacyContentMapper.Column::columnName).toList();
                        if (keys.isEmpty()) throw new ApiException(409, "PRIVACY_UNKEYED_STORAGE",
                                "发现无法逐行定位的正文存储，清除前必须先补齐存储定位。");
                        String rowKey = entry.getKey() + ":" + json.writeValueAsString(keyValues(value, keys));
                        if (!rows.containsKey(rowKey)) {
                            contentBytes += json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
                            if (rows.size() >= MAX_ROWS || contentBytes > MAX_CONTENT_BYTES) throw capacity();
                            rows.put(rowKey, new Row(entry.getKey(), value, keys));
                        }
                        inferRoots(value, entry.getKey(), roots, identities, owner);
                    }
                }
            }
            for (var root : roots.entrySet()) identities.addAll(root.getValue());
            for (Row row : rows.values()) {
                boolean ownedHistory = row.table().startsWith("material_")
                        && (identities.contains(string(row.values(), "excerpt_id"))
                            || identities.contains(string(row.values(), "run_id")));
                if ((owned(row, roots) || ownedHistory)
                        && row.values().get("id") instanceof String value && uuidLike(value)) {
                    identities.add(value);
                }
            }
            if (wave == 63 && identities.stream().anyMatch(value -> !scannedIdentities.contains(value))) throw capacity();
        }

        List<ObjectInfo> objects = new ArrayList<>();
        List<PrivacyLedger.Subject> subjects = new ArrayList<>();
        for (var entry : roots.entrySet()) {
            for (String rootId : entry.getValue().stream().sorted().toList()) {
                ObjectInfo info = object(owner, entry.getKey(), rootId);
                subjects.add(new PrivacyLedger.Subject(entry.getKey(), rootId));
                objects.add(new ObjectInfo(info.objectKind(), info.objectId(), info.title(), info.status(), info.revision(),
                        entry.getKey().equals(kind) && rootId.equals(id) ? "ROOT_ALL_VERSIONS" : "WHOLE_MIXED_DERIVED_DOCUMENT"));
            }
        }
        List<Change> changes = new ArrayList<>();
        List<PrivacyLedger.Redaction> redactions = new ArrayList<>();
        Map<String, Set<String>> fieldsByTable = new TreeMap<>();
        Map<String, Integer> countByTable = new TreeMap<>();
        for (Row row : rows.values()) {
            Map<String, Object> replacements = replacements(row, schema.get(row.table()), roots, identities);
            if (replacements.isEmpty()) continue;
            Map<String, Object> keys = keyValues(row.values(), row.keys());
            changes.add(new Change(row.table(), keys, replacements));
            Map<String, String> ledgerKeys = new TreeMap<>();
            for (var key : keys.entrySet()) {
                String value = String.valueOf(key.getValue());
                // 导入包的 source_id 可能是用户文件路径；账本只保留可定位的摘要，不复制该私有文本。
                ledgerKeys.put(key.getKey(), (uuidLike(value) || value.matches("(?:[0-9]{1,19}|[A-Z_]{1,40})"))
                        ? value : "sha256:" + PrivacyLedger.digest(value));
            }
            List<String> fullFields = replacements.keySet().stream()
                    .filter(field -> wholeField(row, field, roots, identities)).sorted().toList();
            List<String> referenceFields = replacements.keySet().stream()
                    .filter(field -> contentColumn(schema.get(row.table()).stream()
                            .filter(column -> column.columnName().equals(field)).findFirst().orElseThrow()))
                    .filter(field -> !wholeField(row, field, roots, identities)).sorted().toList();
            // 长度和内容摘要由清除后的正文字段重算，账本不把派生校验值误当成正文定位。
            fullFields = fullFields.stream().filter(field -> contentColumn(schema.get(row.table()).stream()
                    .filter(column -> column.columnName().equals(field)).findFirst().orElseThrow())).toList();
            if (!fullFields.isEmpty()) redactions.add(new PrivacyLedger.Redaction(row.table(), Map.copyOf(ledgerKeys),
                    fullFields, true, List.of()));
            if (!referenceFields.isEmpty()) {
                List<String> references = identities.stream().filter(identity -> referenceFields.stream().anyMatch(field ->
                        String.valueOf(row.values().get(field)).contains(identity))).sorted().toList();
                redactions.add(new PrivacyLedger.Redaction(row.table(), Map.copyOf(ledgerKeys),
                        referenceFields, false, references));
            }
            fieldsByTable.computeIfAbsent(row.table(), ignored -> new LinkedHashSet<>()).addAll(replacements.keySet());
            countByTable.merge(row.table(), 1, Integer::sum);
        }
        if (redactions.size() > 2_000 || subjects.size() > 500) throw capacity();
        List<RowImpact> storage = fieldsByTable.entrySet().stream()
                .map(entry -> new RowImpact(entry.getKey(), countByTable.get(entry.getKey()),
                        entry.getValue().stream().sorted().toList(), "CLEAR_CONFIRMED_CONTENT_KEEP_IDENTITY")).toList();
        String digest = PrivacyLedger.digest(json.writeValueAsString(List.of("PURGE", kind, id,
                objects, rows.values().stream().toList(), changes)));
        Preview preview = new Preview("PURGE", kind, id, digest, List.copyOf(objects), storage,
                List.of("混合派生文档在清单中按整份正文处理；确认前请先另存需要保留的独立部分。",
                        "普通知识关联、正文链接、白板视图实例不会触发删除独立笔记；只处理相应引用快照。",
                        "独立账本不保存正文。恢复旧备份时按已登记字段和引用身份重放；独立文档的无关内容保持原样。",
                        "范围依据已登记身份及其可追溯副本；未登记的手工复制、改写和系统外材料无法自动证明关联。",
                        "已下载的导出、供应商已处理的请求和运维备份不能承诺即时消失；服务器保留策略单独管理。"),
                redactions.size());
        return new Plan(preview, List.copyOf(subjects), List.copyOf(redactions), List.copyOf(changes));
    }

    public void apply(String owner, Plan plan) {
        for (Change change : plan.changes()) {
            int changed = mapper.update(owner, change.table(), new ArrayList<>(change.keys().keySet()),
                    change.keys(), new ArrayList<>(change.values().keySet()), change.values());
            if (changed != 1) throw new ApiException(409, "PRIVACY_SCOPE_CHANGED", "清除对象已变化，必须重新预览。");
        }
        for (var subject : plan.subjects()) {
            Spec spec = spec(subject.kind());
            if (Set.of("KNOWLEDGE", "PREPARATION", "QUESTION", "MEMORY_CARD", "QUESTION_COLLECTION", "RECOLLECTION")
                    .contains(subject.kind())) {
                mapper.update(owner, spec.table(), List.of(spec.idColumn()), Map.of(spec.idColumn(), subject.id()),
                        List.of("status"), Map.of("status", "ARCHIVED"));
            } else if ("AI_AUTHORIZATION".equals(subject.kind())) {
                var grant = normalize(mapper.one(owner, spec.table(), spec.idColumn(), subject.id()));
                if (grant != null && "ACTIVE".equals(string(grant, "state"))) {
                    mapper.update(owner, spec.table(), List.of("owner_id", "id"),
                            Map.of("owner_id", owner, "id", subject.id()),
                            List.of("state", "revision", "revoked_at"),
                            Map.of("state", "REVOKED", "revision", 1, "revoked_at", LocalDateTime.now(ZoneOffset.UTC)));
                }
            }
        }
    }

    /** 由封闭恢复流程使用，定位来自已验证的独立账本，而非导出包内任意 SQL。 */
    public void replayRedactions(String owner, PrivacyLedger.Entry entry) {
        Map<String, List<PrivacyContentMapper.Column>> schema = schema();
        for (var redaction : entry.mutation().redactions()) {
            List<PrivacyContentMapper.Column> columns = schema.get(redaction.table());
            if (columns == null) throw new ApiException(409, "PRIVACY_REPLAY_SCHEMA_MISMATCH", "账本引用的正文表在当前恢复结构中不存在。");
            Map<String, PrivacyContentMapper.Column> byName = new LinkedHashMap<>();
            columns.forEach(column -> byName.put(column.columnName(), column));
            List<String> actualKeys = columns.stream().filter(PrivacyContentMapper.Column::primaryKey)
                    .map(PrivacyContentMapper.Column::columnName).toList();
            if (!Set.copyOf(actualKeys).equals(redaction.key().keySet())) {
                throw new ApiException(409, "PRIVACY_REPLAY_SCHEMA_MISMATCH", "账本定位与当前恢复结构不一致。");
            }
            Map<String, Object> keys = new LinkedHashMap<>(redaction.key());
            Map<String, Object> existing = normalize(mapper.keyed(owner, redaction.table(), actualKeys, keys));
            if (existing == null) continue;
            Map<String, Object> values = new LinkedHashMap<>();
            for (String field : redaction.columns()) {
                var column = byName.get(field);
                if (column == null || column.primaryKey() || column.generated() || !contentColumn(column)) {
                    throw new ApiException(409, "PRIVACY_REPLAY_SCHEMA_MISMATCH", "账本清除字段与当前结构不一致。");
                }
                // 恢复不能依赖旧副本中保留的片段；其余结构身份留在关系表和无正文墓碑中。
                values.put(field, recoveryReplacement(column, existing.get(field), redaction.wholeFields(),
                        Set.copyOf(redaction.referenceIdentities())));
            }
            derivedValues(redaction.table(), existing, values);
            mapper.updateLocated(owner, redaction.table(), actualKeys, keys, new ArrayList<>(values.keySet()), values, true);
        }
    }

    private Map<String, List<PrivacyContentMapper.Column>> schema() {
        Map<String, List<PrivacyContentMapper.Column>> result = new TreeMap<>();
        for (var column : mapper.columns()) {
            if (SKIP_TABLES.contains(column.tableName()) || column.tableName().startsWith("privacy_")) continue;
            result.computeIfAbsent(column.tableName(), ignored -> new ArrayList<>()).add(column);
        }
        return result;
    }

    private void inferRoots(Map<String, Object> row, String table, Map<String, LinkedHashSet<String>> roots,
                            Set<String> identities, String owner) {
        if (table.startsWith("ai_") && (table.endsWith("_source") || table.endsWith("_input")
                || table.endsWith("_material")) && row.containsKey("task_id")) {
            add(roots, "AI_TASK", string(row, "task_id"));
        }
        if (table.equals("ai_task")) {
            add(roots, "AI_TASK", string(row, "id"));
        }
        if (table.equals("review_feedback")
                && (identities.contains(string(row, "session_id")) || identities.contains(string(row, "answer_id")))) {
            add(roots, "REVIEW_FEEDBACK", string(row, "id"));
        }
        if (table.equals("review_recollection_question")
                && roots.getOrDefault("RECOLLECTION", new LinkedHashSet<>()).contains(string(row, "recollection_id"))) {
            add(roots, "QUESTION", string(row, "question_id"));
        }
        if (table.equals("preparation_task")
                && roots.getOrDefault("AI_TASK", new LinkedHashSet<>()).contains(string(row, "source_ai_task_id"))) {
            add(roots, "PREPARATION_TASK", string(row, "id"));
        }
        if (table.equals("question_collection_step")
                && roots.getOrDefault("QUESTION", new LinkedHashSet<>()).contains(string(row, "question_id"))) {
            add(roots, "QUESTION_COLLECTION", string(row, "collection_id"));
        }
        if (table.equals("review_action_source")
                && (identities.contains(string(row, "feedback_id")) || identities.contains(string(row, "task_id"))
                    || identities.contains(string(row, "session_id")) || identities.contains(string(row, "answer_id"))
                    || identities.contains(string(row, "recollection_id"))
                    || identities.contains(string(row, "origin_action_id")))) {
            add(roots, "REVIEW_ACTION", string(row, "action_id"));
        }
        if (table.equals("memory_card_source") && containsValue(row, roots.get("KNOWLEDGE"))) {
            add(roots, "MEMORY_CARD", string(row, "card_id"));
        }
        if (table.equals("preparation_reference") && !"COLLECTION".equals(string(row, "source_kind"))
                && ("FACT_BASIS".equals(string(row, "use_type")) || expression(owner, string(row, "target_item_id")))) {
            add(roots, "PREPARATION", string(row, "target_item_id"));
        }
        if (table.equals("preparation_material_reference")) {
            add(roots, "PREPARATION", string(row, "item_id"));
        }
        if (table.startsWith("ai_") && table.endsWith("_decision") && "ADOPTED".equals(string(row, "status"))
                && roots.getOrDefault("AI_TASK", new LinkedHashSet<>()).contains(string(row, "task_id"))) {
            switch (table) {
                case "ai_candidate_decision" -> add(roots, "KNOWLEDGE", string(row, "target_item_id"));
                case "ai_question_decision" -> add(roots, "QUESTION", string(row, "target_question_id"));
                case "ai_expression_decision", "ai_fact_draft_decision", "ai_jd_requirement_decision" ->
                        add(roots, "PREPARATION", string(row, "target_item_id"));
                case "ai_card_draft_decision" -> add(roots, "MEMORY_CARD", string(row, "target_card_id"));
                default -> { }
            }
        }
        if (table.equals("ai_scope_authorization")) add(roots, "AI_AUTHORIZATION", string(row, "id"));
        for (var root : roots.entrySet()) identities.addAll(root.getValue());
    }

    private boolean expression(String owner, String id) {
        if (id == null) return false;
        var row = normalize(mapper.one(owner, "preparation_item", "id", id));
        return row != null && "EXPRESSION".equals(string(row, "kind"));
    }

    private static boolean owned(Row row, Map<String, LinkedHashSet<String>> roots) {
        String kind;
        String column;
        String table = row.table();
        if (table.equals("knowledge_item")) { kind = "KNOWLEDGE"; column = "id"; }
        else if (Set.of("knowledge_draft", "knowledge_version", "knowledge_organization",
                "knowledge_reference_scan", "knowledge_source_revision", "knowledge_text_import",
                "knowledge_package_entry").contains(table)) { kind = "KNOWLEDGE"; column = "item_id"; }
        else if (table.equals("preparation_item")) { kind = "PREPARATION"; column = "id"; }
        else if (Set.of("preparation_version", "preparation_fact_decision").contains(table)) { kind = "PREPARATION"; column = "item_id"; }
        else if (table.equals("question")) { kind = "QUESTION"; column = "id"; }
        else if (table.equals("question_version")) { kind = "QUESTION"; column = "question_id"; }
        else if (table.equals("question_collection")) { kind = "QUESTION_COLLECTION"; column = "id"; }
        else if (table.startsWith("question_collection_")) { kind = "QUESTION_COLLECTION"; column = "collection_id"; }
        else if (table.equals("preparation_task")) { kind = "PREPARATION_TASK"; column = "id"; }
        else if (table.equals("preparation_task_history")) { kind = "PREPARATION_TASK"; column = "task_id"; }
        else if (table.equals("review_action")) { kind = "REVIEW_ACTION"; column = "id"; }
        else if (table.startsWith("review_action_")) { kind = "REVIEW_ACTION"; column = "action_id"; }
        else if (table.equals("interview_event")) { kind = "INTERVIEW_EVENT"; column = "id"; }
        else if (table.startsWith("interview_event_")) { kind = "INTERVIEW_EVENT"; column = "event_id"; }
        else if (table.equals("memory_card")) { kind = "MEMORY_CARD"; column = "id"; }
        else if (table.startsWith("memory_") && row.values().containsKey("card_id")) { kind = "MEMORY_CARD"; column = "card_id"; }
        else if (table.equals("knowledge_board")) { kind = "BOARD"; column = "id"; }
        else if (table.equals("knowledge_board_revision")) { kind = "BOARD"; column = "board_id"; }
        else if (table.equals("material_attachment")) { kind = "MATERIAL"; column = "id"; }
        else if (table.startsWith("material_") && row.values().containsKey("attachment_id")) { kind = "MATERIAL"; column = "attachment_id"; }
        else if (table.equals("ai_task")) { kind = "AI_TASK"; column = "id"; }
        else if (table.startsWith("ai_") && row.values().containsKey("task_id")) { kind = "AI_TASK"; column = "task_id"; }
        else if (table.equals("training_session")) { kind = "TRAINING"; column = "id"; }
        else if (table.startsWith("training_") && row.values().containsKey("session_id")) { kind = "TRAINING"; column = "session_id"; }
        else if (table.equals("review_feedback")) { kind = "REVIEW_FEEDBACK"; column = "id"; }
        else if (table.startsWith("review_feedback_")) { kind = "REVIEW_FEEDBACK"; column = "feedback_id"; }
        else if (table.equals("review_recollection")) { kind = "RECOLLECTION"; column = "id"; }
        else if (table.startsWith("review_recollection_")) { kind = "RECOLLECTION"; column = "recollection_id"; }
        else return false;
        return roots.getOrDefault(kind, new LinkedHashSet<>()).contains(string(row.values(), column));
    }

    private Map<String, Object> replacements(Row row, List<PrivacyContentMapper.Column> columns,
                                             Map<String, LinkedHashSet<String>> roots, Set<String> identities) {
        // 这些回执仅含固定许可元数据；保留被撤回身份和真实状态，不把枚举改写成正文占位。
        if ("command_receipt".equals(row.table())
                && PrivacyPermissionMetadata.isSafePermissionMetadata(string(row.values(), "operation"))) return Map.of();
        Map<String, Object> replacement = new LinkedHashMap<>();
        for (var column : columns) {
            if (column.primaryKey() || column.generated() || !contentColumn(column)) continue;
            Object original = row.values().get(column.columnName());
            if (!(original instanceof String text) || text.isEmpty()) continue;
            boolean jsonColumn = column.columnName().endsWith("_json") || "json".equals(column.dataType());
            String next;
            if (jsonColumn) {
                JsonNode value;
                try { value = json.readTree(text); }
                catch (RuntimeException exception) { throw new ApiException(409, "PRIVACY_INVALID_STORED_JSON", "发现无法安全定位的历史内容，须先修复后再清除。"); }
                boolean whole = wholeField(row, column.columnName(), roots, identities);
                JsonNode sanitized = whole && "ai_task".equals(row.table()) && "manifest_json".equals(column.columnName())
                        ? sanitizeManifest(text, identities) : sanitize(value, whole, identities, null);
                if (value.equals(sanitized)) continue;
                next = json.writeValueAsString(sanitized);
            } else if (wholeField(row, column.columnName(), roots, identities)) {
                next = marker(column);
            } else {
                next = text;
                for (String identity : identities) {
                    next = next.replace("knowledge:" + identity, "knowledge-cleared:" + identity)
                            .replace("material:" + identity, "material-cleared:" + identity);
                }
            }
            if (!next.equals(text)) replacement.put(column.columnName(), next);
        }
        derivedValues(row.table(), row.values(), replacement);
        return replacement;
    }

    private static boolean wholeField(Row row, String field, Map<String, LinkedHashSet<String>> roots,
                                      Set<String> identities) {
        if (owned(row, roots)) return true;
        if ("knowledge_concept_merge".equals(row.table())) {
            boolean source = identities.contains(string(row.values(), "source_item_id"));
            boolean target = identities.contains(string(row.values(), "target_item_id"));
            return "source_title".equals(field) ? source : "target_title".equals(field) ? target
                    : "choices_json".equals(field) && (source || target);
        }
        if ("target_title".equals(field)) return identities.contains(string(row.values(), "target_item_id"));
        if ("root_title".equals(field)) return identities.contains(string(row.values(), "root_item_id"));
        if ("source_title".equals(field)) return identities.contains(string(row.values(), "source_item_id"));
        if ("title".equals(field) && "expression_basis_review_fact".equals(row.table())) {
            return identities.contains(string(row.values(), "fact_item_id"));
        }
        if ("file_name".equals(field) && "knowledge_text_import_batch_entry".equals(row.table())) {
            return identities.contains(string(row.values(), "import_record_id"));
        }
        if (row.table().startsWith("material_")
                && (identities.contains(string(row.values(), "excerpt_id"))
                    || identities.contains(string(row.values(), "run_id")))) return true;
        return copyField(field) && containsValue(row.values(), identities);
    }

    /** 只重算正文派生值；历史原文件摘要／收费审计摘要仍代表原始证据，不冒充清除后的文件。 */
    void derivedValues(String table, Map<String, Object> existing, Map<String, Object> values) {
        if ("ai_task".equals(table) && values.containsKey("manifest_json")) {
            AiDtos.Manifest manifest;
            try { manifest = json.readValue(String.valueOf(values.get("manifest_json")), AiDtos.Manifest.class); }
            catch (RuntimeException exception) { throw new ApiException(409, "PRIVACY_MANIFEST_SHAPE_INVALID", "清除后的任务清单不能保持已登记结构，操作尚未提交。"); }
            values.put("manifest_hash", new AiManifestDigest(json).hash(manifest));
        }
        if ("memory_card_version".equals(table) && (!values.isEmpty())) {
            try {
                String encoded = String.valueOf(values.getOrDefault("prompt_json", existing.get("prompt_json")));
                MemoryDtos.Prompt prompt = json.readValue(encoded, MemoryDtos.Prompt.class);
                MemoryDtos.Content content = new MemoryDtos.Content(prompt.kind(), prompt.title(), prompt.prompt(),
                        String.valueOf(values.getOrDefault("reference_text", existing.get("reference_text"))),
                        prompt.sources(), prompt.relatedConceptIds());
                values.put("content_bytes", json.writeValueAsBytes(content).length);
            } catch (RuntimeException exception) {
                throw new ApiException(409, "PRIVACY_MEMORY_SHAPE_INVALID", "清除后的回忆卡不能保持已登记结构，操作尚未提交。");
            }
        }
    }

    private JsonNode sanitize(JsonNode value, boolean force, Set<String> identities, String key) {
        if (value == null || value.isNull() || value.isNumber() || value.isBoolean()) return value;
        if (value.isTextual()) {
            String text = value.asText();
            if (text.isEmpty()) return value;
            if (force && !protectedValue(key, text)) return json.valueToTree(MARKER);
            String next = text;
            for (String id : identities) next = next.replace("knowledge:" + id, "knowledge-cleared:" + id)
                    .replace("material:" + id, "material-cleared:" + id);
            return json.valueToTree(next);
        }
        if (value.isArray()) {
            ArrayNode result = json.createArrayNode();
            for (JsonNode item : value) result.add(sanitize(item, force, identities, key));
            return result;
        }
        if (value.isObject()) {
            boolean containsSelectedIdentity = false;
            for (var property : value.properties()) {
                if (property.getValue().isTextual() && identityKey(property.getKey())
                        && identities.contains(property.getValue().asText())) containsSelectedIdentity = true;
            }
            ObjectNode result = json.createObjectNode();
            for (var property : value.properties()) {
                result.set(property.getKey(), sanitize(property.getValue(), force || containsSelectedIdentity,
                        identities, property.getKey()));
            }
            return result;
        }
        throw new ApiException(409, "PRIVACY_UNKNOWN_JSON_TYPE", "历史内容存在无法安全处理的数据类型。");
    }

    private JsonNode sanitizeManifest(String encoded, Set<String> identities) {
        try {
            // 具体枚举由登记 DTO 身份保留，不能把任意看似 UUID／哈希／日期的正文字符串当成控制值。
            return sanitizeTyped(json.readValue(encoded, AiDtos.Manifest.class), identities, null);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new ApiException(409, "PRIVACY_MANIFEST_SHAPE_INVALID",
                    "历史任务清单不能保持已登记结构，须先修复后再清除。");
        }
    }

    private JsonNode sanitizeTyped(Object value, Set<String> identities, String key) throws ReflectiveOperationException {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>
                || value instanceof TemporalAccessor) return json.valueToTree(value);
        if (value instanceof String text) return sanitize(json.valueToTree(text), true, identities, key);
        if (value instanceof List<?> list) {
            ArrayNode result = json.createArrayNode();
            for (Object item : list) result.add(sanitizeTyped(item, identities, key));
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            ObjectNode result = json.createObjectNode();
            for (var entry : map.entrySet()) {
                String name = String.valueOf(entry.getKey());
                result.set(name, sanitizeTyped(entry.getValue(), identities, name));
            }
            return result;
        }
        if (value.getClass().isRecord()) {
            ObjectNode result = json.createObjectNode();
            for (var component : value.getClass().getRecordComponents()) {
                result.set(component.getName(), sanitizeTyped(component.getAccessor().invoke(value), identities, component.getName()));
            }
            return result;
        }
        throw new IllegalArgumentException("未登记的任务清单字段类型");
    }

    Object recoveryReplacement(PrivacyContentMapper.Column column, Object original, boolean force,
                               Set<String> referenceIdentities) {
        if (original == null) return null;
        if (column.columnName().endsWith("_json") || "json".equals(column.dataType())) {
            if (force && "ai_task".equals(column.tableName()) && "manifest_json".equals(column.columnName())) {
                return json.writeValueAsString(sanitizeManifest(String.valueOf(original), referenceIdentities));
            }
            JsonNode existing;
            try { existing = json.readTree(String.valueOf(original)); }
            catch (RuntimeException exception) { throw new ApiException(409, "PRIVACY_REPLAY_INVALID_JSON", "旧副本结构不可证明，恢复继续封闭。"); }
            return json.writeValueAsString(sanitize(existing, force, referenceIdentities, null));
        }
        if (force) return marker(column);
        String value = String.valueOf(original);
        for (String id : referenceIdentities) {
            value = value.replace("knowledge:" + id, "knowledge-cleared:" + id)
                    .replace("material:" + id, "material-cleared:" + id);
        }
        return value;
    }

    private static boolean protectedValue(String key, String value) {
        return key != null && (identityKey(key) || KEEP_JSON_KEYS.contains(key)
                || key.endsWith("At") || key.endsWith("_at") || key.endsWith("Date") || key.endsWith("_date"));
    }
    private static boolean identityKey(String key) {
        return key != null && (key.equals("id") || key.endsWith("Id") || key.endsWith("_id")
                || key.endsWith("Ids") || key.endsWith("_ids"));
    }
    private static boolean copyField(String field) {
        return Set.of("source_title", "excerpt", "reference_text").contains(field);
    }
    private static boolean contentColumn(PrivacyContentMapper.Column column) {
        if ("verification".equals(column.columnName()) && !Set.of("review_action_source", "review_source_judgment")
                .contains(column.tableName())) return false;
        if ("purpose".equals(column.columnName()) && "question_collection_step".equals(column.tableName())) return true;
        return PrivacyContentMapper.Sql.text(column.dataType()) && (CONTENT.contains(column.columnName())
                || column.columnName().endsWith("_json"));
    }
    private static String marker(PrivacyContentMapper.Column column) {
        long max = column.maximumLength() == null ? Long.MAX_VALUE : column.maximumLength();
        return max >= MARKER.length() ? MARKER : "X".repeat(Math.toIntExact(Math.max(1, max)));
    }
    private static void add(Map<String, LinkedHashSet<String>> roots, String kind, String id) {
        if (id != null && uuidLike(id)) roots.computeIfAbsent(kind, ignored -> new LinkedHashSet<>()).add(id);
    }
    private static boolean containsValue(Map<String, Object> row, Set<String> ids) {
        return ids != null && row.values().stream().anyMatch(value -> value instanceof String text && ids.contains(text));
    }
    public static PrivacyActionableKind actionableKind(String value) {
        try {
            if (value == null) throw new IllegalArgumentException();
            PrivacyActionableKind kind = PrivacyActionableKind.valueOf(value);
            if (!SPECS.containsKey(kind.name())) throw new IllegalArgumentException();
            return kind;
        } catch (IllegalArgumentException exception) {
            throw new ApiException(400, "INVALID_PRIVACY_KIND", "不支持此隐私对象类型。");
        }
    }

    private static Spec spec(String kind) {
        Spec spec = SPECS.get(kind);
        if (spec == null) throw new ApiException(400, "INVALID_PRIVACY_KIND", "不支持此隐私对象类型。");
        return spec;
    }
    private static Map<String, Object> keyValues(Map<String, Object> values, List<String> keys) {
        Map<String, Object> result = new TreeMap<>();
        for (String key : keys) {
            Object value = values.get(key);
            if (value == null) throw new ApiException(409, "PRIVACY_UNKEYED_STORAGE", "隐私内容缺少稳定定位。");
            result.put(key, value);
        }
        return result;
    }
    private static Map<String, Object> normalize(Map<String, Object> raw) {
        if (raw == null) return null;
        Map<String, Object> result = new TreeMap<>();
        for (var entry : raw.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Timestamp timestamp) value = timestamp.toLocalDateTime().toString();
            else if (value instanceof TemporalAccessor temporal) value = temporal.toString();
            else if (value instanceof byte[] bytes) value = new String(bytes, StandardCharsets.UTF_8);
            result.put(entry.getKey().toLowerCase(Locale.ROOT), value);
        }
        return result;
    }
    private static String string(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }
    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String string) {
            try { return Long.parseLong(string); } catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }
    private static boolean uuidLike(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    private static ApiException capacity() {
        return new ApiException(422, "PRIVACY_SCOPE_CAPACITY",
                "此次依赖范围超过单次安全处理容量，尚未清除任何正文；请先拆分来源或由服务器离线流程处理。");
    }
}
