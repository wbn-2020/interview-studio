package com.wenjie.studio.search;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.material.MaterialProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.wenjie.studio.search.UnifiedSearchDtos.*;

@Service
public class UnifiedSearchSources {
    private final UnifiedSearchMapper mapper;
    private final MaterialProperties materials;
    private final JsonMapper json;
    private final UnifiedSearchHistorySources history;
    private final UnifiedSearchAiSources ai;

    public UnifiedSearchSources(UnifiedSearchMapper mapper, MaterialProperties materials, JsonMapper json,
                                UnifiedSearchHistorySources history, UnifiedSearchAiSources ai) {
        this.mapper = mapper;
        this.materials = materials;
        this.json = json;
        this.history = history;
        this.ai = ai;
    }

    public void requireEnabled(Domain domain) {
        if (domain == null) throw invalid();
        if (domain == Domain.MATERIAL_EXCERPT && !materials.enabled()) {
            throw new ApiException(503, "MATERIALS_DISABLED", "服务器尚未启用附件资料。");
        }
    }

    // 知识列表会把确切来源的 404 降级为不完整页；只读业务异常不标记外层事务回滚。
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = ApiException.class)
    public Source read(String owner, Domain domain, String id, String versionId) {
        requireEnabled(domain);
        uuid(owner); uuid(id); uuid(versionId);
        if (domain == Domain.MATERIAL_EXCERPT) {
            if (!id.equals(versionId)) throw missing();
            var row = mapper.excerpt(owner, id);
            if (row == null) throw missing();
            if (!validExcerpt(row)) throw broken();
            return excerpt(row);
        }
        var row = switch (domain) {
            case KNOWLEDGE -> mapper.knowledgeVersion(owner, id, versionId);
            case PREPARATION -> mapper.preparationVersion(owner, id, versionId);
            case QUESTION -> mapper.questionVersion(owner, id, versionId);
            default -> throw invalid();
        };
        if (row == null) throw missing();
        return version(domain, row);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = ApiException.class)
    public Source read(String owner, Domain domain, String id, String versionId, Integer revision) {
        if (domain == Domain.TRAINING_ANSWER || domain == Domain.REVIEW_FEEDBACK || domain == Domain.RECOLLECTION) {
            return history.read(owner, domain, id, versionId, revision);
        }
        if (revision != null) throw invalid();
        return read(owner, domain, id, versionId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = ApiException.class)
    public Source read(String owner, Domain domain, String id, String versionId, Integer revision,
                       String feedbackId, String reportHash) {
        if (domain == Domain.AI_FEEDBACK) {
            if (revision != null) throw invalid();
            return ai.read(owner, id, versionId, feedbackId, reportHash);
        }
        if (feedbackId != null || reportHash != null) throw invalid();
        return read(owner, domain, id, versionId, revision);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Source> candidates(String owner, Domain domain, String status, String query, String afterId, int limit) {
        requireEnabled(domain);
        uuid(owner);
        if (afterId != null) uuid(afterId);
        if (domain == Domain.KNOWLEDGE || !("ACTIVE".equals(status) || "ARCHIVED".equals(status))
                || query == null || query.isBlank() || query.length() > 100 || limit < 1 || limit > 100) throw invalid();
        if (domain == Domain.MATERIAL_EXCERPT && status.equals("ARCHIVED")) return List.of();
        String pattern = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        // 状态筛选之前核同一候选范围的基线，不能把升级不完整伪装成没有命中。
        if (domain == Domain.MATERIAL_EXCERPT && mapper.incompleteExcerptBaseline(owner, pattern, afterId)) throw broken();
        return switch (domain) {
            case PREPARATION -> mapper.preparations(owner, status, pattern, afterId, limit).stream()
                    .map(row -> version(domain, row)).toList();
            case QUESTION -> mapper.questions(owner, status, pattern, afterId, limit).stream()
                    .map(row -> version(domain, row)).toList();
            case MATERIAL_EXCERPT -> mapper.excerpts(owner, pattern, afterId, limit).stream().map(row -> {
                // 不能在批次后静默丢弃损坏记录，否则末项游标和耗尽判断会提前截断结果。
                if (!validExcerpt(row)) throw broken();
                return excerpt(row);
            }).toList();
            case TRAINING_ANSWER, REVIEW_FEEDBACK, RECOLLECTION ->
                    history.candidates(owner, domain, status, query, afterId, limit);
            default -> throw invalid();
        };
    }

    private Source version(Domain domain, UnifiedSearchRows.VersionRow row) {
        var fields = new ArrayList<TextField>();
        add(fields, "title", "标题", row.title());
        if (domain == Domain.KNOWLEDGE) {
            add(fields, "body", "正文", row.body());
        } else {
            JsonNode root = content(row.contentJson());
            if (!root.path("title").isTextual() || !row.title().equals(root.path("title").asText())) throw broken();
            if (domain == Domain.QUESTION) {
                text(fields, root, "prompt", "题面");
                text(fields, root, "conditions", "作答条件");
                text(fields, root, "technology", "技术版本");
                text(fields, root, "scenario", "场景");
                tags(fields, root);
            } else {
                switch (row.kind()) {
                    case "TARGET" -> {
                        text(fields, root, "company", "公司备注");
                        text(fields, root, "jd", "岗位描述");
                        JsonNode requirements = array(root, "requirements", 50);
                        for (int index = 0; index < requirements.size(); index++) {
                            JsonNode requirement = requirements.get(index);
                            if (!requirement.isObject() || !requirement.path("text").isTextual()) throw broken();
                            add(fields, "requirements." + index + ".text", "要求 " + (index + 1), requirement.path("text").asText());
                        }
                    }
                    case "PROJECT" -> {
                        text(fields, root, "overview", "项目概述");
                        text(fields, root, "technology", "技术版本");
                    }
                    case "FACT" -> {
                        text(fields, root, "scenario", "场景");
                        text(fields, root, "problem", "问题");
                        text(fields, root, "action", "行动");
                        text(fields, root, "result", "结果");
                        text(fields, root, "technology", "技术版本");
                        tags(fields, root);
                    }
                    case "EXPRESSION" -> {
                        text(fields, root, "body", "表达正文");
                        tags(fields, root);
                    }
                    default -> throw broken();
                }
            }
        }
        // 当前核实和父项目状态只是元数据；不会认证历史版本或表达所依赖的事实。
        boolean fact = domain == Domain.PREPARATION && "FACT".equals(row.kind());
        Hit hit = new Hit(domain, row.id(), row.versionId(), row.versionNumber(), row.kind(), row.title(),
                row.status(), row.versionId().equals(row.currentVersionId()), fact ? row.verification() : null,
                fact ? row.projectStatus() : null, null, null, List.of());
        return new Source(hit, List.copyOf(fields));
    }

    private static Source excerpt(UnifiedSearchRows.ExcerptRow row) {
        Hit hit = new Hit(Domain.MATERIAL_EXCERPT, row.id(), row.id(), null, "EXCERPT", row.fileName(), "READY",
                true, null, null, row.attachmentId(), row.runId(), List.of());
        return new Source(hit, List.of(new TextField("locator", "原文位置", row.locator()),
                new TextField("text", "摘录正文", row.text())));
    }

    private static boolean validExcerpt(UnifiedSearchRows.ExcerptRow row) {
        if (row.locator() == null || row.text() == null || row.fileName() == null) return false;
        if ("MANUAL".equals(row.origin())) {
            return row.runId() == null && row.segmentId() == null && row.startOffset() == null && row.endOffset() == null;
        }
        if (!"PARSED".equals(row.origin()) || row.startOffset() == null || row.endOffset() == null
                || row.segmentText() == null || !row.locator().equals(row.segmentLocator())) return false;
        // SQL 固定 owner、附件摘要和原 run/segment；UTF-16 选区须用 Java 原文复核，不能用数据库字符数替代。
        int start = row.startOffset(), end = row.endOffset();
        String original = row.segmentText();
        return start >= 0 && end > start && end <= original.length()
                && !splitsSurrogate(original, start) && !splitsSurrogate(original, end)
                && row.text().equals(original.substring(start, end));
    }

    private JsonNode content(String value) {
        if (value == null) throw broken();
        try {
            JsonNode node = json.readTree(value);
            if (node == null || !node.isObject()) throw broken();
            return node;
        } catch (JacksonException exception) { throw broken(); }
    }

    private static void text(List<TextField> fields, JsonNode root, String key, String label) {
        JsonNode node = root.path(key);
        if (!node.isTextual()) throw broken();
        add(fields, key, label, node.asText());
    }

    private static JsonNode array(JsonNode root, String key, int maximum) {
        JsonNode node = root.path(key);
        if (!node.isArray() || node.size() > maximum) throw broken();
        return node;
    }

    private static void tags(List<TextField> fields, JsonNode root) {
        JsonNode tags = array(root, "tags", 20);
        for (int index = 0; index < tags.size(); index++) {
            if (!tags.get(index).isTextual()) throw broken();
            add(fields, "tags." + index, "标签 " + (index + 1), tags.get(index).asText());
        }
    }

    private static void add(List<TextField> fields, String id, String label, String text) {
        if (text == null) throw broken();
        fields.add(new TextField(id, label, text));
    }

    private static boolean splitsSurrogate(String text, int index) {
        return index > 0 && index < text.length() && Character.isHighSurrogate(text.charAt(index - 1))
                && Character.isLowSurrogate(text.charAt(index));
    }

    private static void uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw invalid(); }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }

    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "请检查来源身份、检索词和分页范围。"); }
    private static ApiException missing() { return new ApiException(404, "SEARCH_SOURCE_NOT_FOUND", "该来源或确切版本不存在或不可访问。"); }
    private static ApiException broken() { return new ApiException(409, "SEARCH_SOURCE_INVALID", "检索来源完整性无法确认，请在原模块检查资料后重试。"); }
}
