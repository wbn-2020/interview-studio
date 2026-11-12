package com.wenjie.studio.memory;

import com.wenjie.studio.common.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.memory.MemoryDtos.*;

final class MemoryRules {
    static final long MAX_REVISION = 1000;
    static final int MAX_VERSION = 1000;
    static final int MAX_CONTENT_BYTES = 192 * 1024;
    static final int MAX_REQUEST_BYTES = 224 * 1024;

    private MemoryRules() {}

    static <T> T decode(JsonMapper json, byte[] raw, Class<T> type) {
        if (raw == null || raw.length == 0) throw invalid("请求必须为 JSON 对象。");
        if (raw.length > MAX_REQUEST_BYTES) throw sizeLimit("回忆卡请求不能超过 224 KiB。");
        JsonNode body;
        try { body = json.readTree(raw); }
        catch (RuntimeException exception) { throw invalid("JSON 格式无效、字段重复或嵌套超限。"); }
        if (type == Create.class) {
            fields(body, "content"); contentShape(body.get("content"));
        } else if (type == Save.class) {
            fields(body, "expectedRevision", "content");
            integer(body.get("expectedRevision")); contentShape(body.get("content"));
        } else if (type == ChangeStatus.class) {
            fields(body, "expectedRevision"); integer(body.get("expectedRevision"));
        } else if (type == EmptyRequest.class) {
            fields(body);
        } else throw new IllegalArgumentException("未定义回忆卡请求类型");
        try { return json.treeToValue(body, type); }
        catch (RuntimeException exception) { throw invalid("请求字段、类型或枚举不符合回忆卡接口要求。"); }
    }

    static int content(JsonMapper json, Content value) {
        required(value); required(value.kind());
        text(value.title(), 200, true); text(value.prompt(), 10_000, true); text(value.reference(), 20_000, true);
        list(value.sources(), 1, 10); list(value.relatedConceptIds(), 0, 20);
        for (SourceLink source : value.sources()) { uuid(source.itemId()); uuid(source.versionId()); }
        value.relatedConceptIds().forEach(MemoryRules::uuid);
        int bytes = json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_CONTENT_BYTES) throw sizeLimit("完整回忆卡内容编码后不能超过 192 KiB。");
        return bytes;
    }

    static <T> T required(T value) {
        if (value == null) throw invalid("必填内容不能为空。");
        return value;
    }

    static String uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException exception) { throw invalid("标识必须为标准小写 UUID。"); }
        return value;
    }

    static String text(String value, int maximum, boolean nonblank) {
        if (value == null || value.codePointCount(0, value.length()) > maximum
                || (nonblank && value.codePoints().allMatch(point -> Character.isWhitespace(point) || Character.isSpaceChar(point)))
                || value.indexOf('\0') >= 0 || value.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF)) {
            throw invalid("文字为空白、超过长度限制或包含无效 Unicode 字符。");
        }
        return value;
    }

    static void revision(Long value) {
        if (value == null || value < 0 || value > MAX_REVISION) throw invalid("预期修订须为 0 至 1000 的整数。");
    }

    static void page(int value, int maximum) {
        if (value < 1 || value > maximum) throw invalid("分页条数超出允许范围。");
    }

    static String pattern(String value) {
        if (value == null) return null;
        text(value, 100, false);
        return value.isEmpty() ? null : "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static void contentShape(JsonNode body) {
        fields(body, "kind", "title", "prompt", "reference", "sources", "relatedConceptIds");
        strings(body, "kind", "title", "prompt", "reference");
        JsonNode sources = body.get("sources"); array(sources, 1, 10);
        for (JsonNode source : sources) { fields(source, "itemId", "versionId"); strings(source, "itemId", "versionId"); }
        JsonNode concepts = body.get("relatedConceptIds"); array(concepts, 0, 20);
        for (JsonNode concept : concepts) {
            if (!concept.isTextual()) throw invalid("相关概念标识必须为字符串。");
            text(concept.stringValue(), Integer.MAX_VALUE, false);
        }
    }

    private static void fields(JsonNode body, String... names) {
        if (body == null || !body.isObject()) throw invalid("请求及嵌套内容必须为 JSON 对象。");
        Set<String> expected = Set.of(names);
        if (body.size() != expected.size()) throw invalid("请求字段缺失或包含未定义字段。");
        for (var field : body.properties()) if (!expected.contains(field.getKey())) throw invalid("请求包含未定义字段。");
        for (String name : names) if (!body.has(name)) throw invalid("请求缺少必填字段。");
    }

    private static void strings(JsonNode body, String... names) {
        for (String name : names) {
            JsonNode value = body.get(name);
            if (!value.isTextual()) throw invalid("文本与枚举字段必须为字符串。");
            text(value.stringValue(), Integer.MAX_VALUE, false);
        }
    }

    private static void integer(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid("修订号必须为有效整数。");
    }

    private static void array(JsonNode value, int minimum, int maximum) {
        if (!value.isArray() || value.size() < minimum || value.size() > maximum) throw invalid("列表数量或类型不符合要求。");
    }

    private static <T> void list(List<T> values, int minimum, int maximum) {
        if (values == null || values.size() < minimum || values.size() > maximum || values.stream().anyMatch(value -> value == null)) {
            throw invalid("列表数量或内容无效。");
        }
        if (new HashSet<>(values).size() != values.size()) throw invalid("列表不能包含重复值。");
    }

    static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    static ApiException invalid(String detail) { return new ApiException(400, "INVALID_REQUEST", detail); }
    static ApiException sizeLimit(String detail) { return new ApiException(422, "MEMORY_LIMIT", detail); }
    static ApiException revisionLimit() { return new ApiException(409, "REVISION_LIMIT", "回忆卡修订或内容版本已达到上限。"); }
    static ApiException missing() { return new ApiException(404, "MEMORY_CARD_NOT_FOUND", "回忆卡或确切版本不存在或不可访问。"); }
}
