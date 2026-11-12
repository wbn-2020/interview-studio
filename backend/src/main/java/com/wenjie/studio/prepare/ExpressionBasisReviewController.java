package com.wenjie.studio.prepare;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import com.wenjie.studio.prepare.ExpressionBasisReviewDtos.Record;
import static com.wenjie.studio.prepare.ExpressionBasisReviewDtos.*;
import static com.wenjie.studio.prepare.ExpressionBasisReviewRules.*;

@RestController
@RequestMapping(value = "/api/v1/prepare/items/{itemId}", produces = MediaType.APPLICATION_JSON_VALUE)
public class ExpressionBasisReviewController {
    private static final Set<String> CONFIRM_FIELDS = Set.of("expectedItemRevision", "expectedVersionId",
            "expectedRevision", "basisChecks", "reason", "confirmed");
    private static final Set<String> CHECK_FIELDS = Set.of("kind", "itemId", "versionId", "state",
            "sourceGeneration", "projectGeneration");
    private final ExpressionBasisReviewService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;

    public ExpressionBasisReviewController(ExpressionBasisReviewService service, CurrentOwner owner) {
        this.service = service; this.owner = owner;
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(5)
                                .maxStringLength(REQUEST_BYTES).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/expression-basis-review")
    public ResponseEntity<State> state(@PathVariable("itemId") String itemId, @RequestParam MultiValueMap<String, String> query) {
        noQuery(query);
        return response(service.state(owner.id(), itemId));
    }

    @PostMapping(value = "/expression-basis-review/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Record> confirm(@PathVariable("itemId") String itemId, @RequestHeader("Idempotency-Key") String key,
                                          @RequestParam MultiValueMap<String, String> query, @RequestBody byte[] raw) {
        noQuery(query);
        return response(service.confirm(owner.id(), itemId, key, decode(raw)));
    }

    @GetMapping("/expression-basis-reviews")
    public ResponseEntity<Page> history(@PathVariable("itemId") String itemId, @RequestParam MultiValueMap<String, String> query) {
        for (var entry : query.entrySet()) {
            if (!Set.of("limit", "before").contains(entry.getKey()) || entry.getValue().size() != 1
                    || entry.getValue().getFirst() == null || entry.getValue().getFirst().isEmpty()) {
                throw invalid("历史查询参数未知、重复或为空。");
            }
        }
        String rawLimit = query.getFirst("limit"), rawBefore = query.getFirst("before");
        if (rawLimit != null && !rawLimit.matches("(?:[1-9]|1[0-9]|20)")) throw invalid("每页数量须为 1 至 20。");
        if (rawBefore != null && !rawBefore.matches("[1-9][0-9]{0,15}")) throw invalid("历史游标须为正安全整数。");
        Long before = rawBefore == null ? null : Long.parseLong(rawBefore);
        if (before != null && before > MAX) throw invalid("历史游标须为正安全整数。");
        return response(service.history(owner.id(), itemId, before, rawLimit == null ? 20 : Integer.parseInt(rawLimit)));
    }

    private Confirm decode(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > REQUEST_BYTES) throw invalid("请求须为不超过 64 KiB 的 JSON 对象。");
        JsonNode body;
        try { body = requests.readTree(raw); }
        catch (RuntimeException exception) { throw invalid("JSON 格式无效、字段重复或嵌套超限。"); }
        fields(body, CONFIRM_FIELDS);
        integer(body.get("expectedItemRevision")); integer(body.get("expectedRevision"));
        textual(body.get("expectedVersionId")); textual(body.get("reason"));
        JsonNode confirmed = body.get("confirmed"), basis = body.get("basisChecks");
        if (!confirmed.isBoolean() || !confirmed.booleanValue()) throw invalid("必须明确确认本次依据复核。");
        if (!basis.isArray() || basis.size() < 1 || basis.size() > 50) throw invalid("事实检查须为 1 至 50 项完整列表。");
        for (JsonNode check : basis) {
            fields(check, CHECK_FIELDS);
            for (String name : Set.of("kind", "itemId", "versionId", "state")) textual(check.get(name));
            integer(check.get("sourceGeneration")); integer(check.get("projectGeneration"));
        }
        try { return requests.treeToValue(body, Confirm.class); }
        catch (RuntimeException exception) { throw invalid("确认字段、类型或枚举无效。"); }
    }

    private static void fields(JsonNode object, Set<String> expected) {
        if (object == null || !object.isObject() || object.size() != expected.size()) throw invalid("必须提供完整且无未知字段的对象。");
        for (var entry : object.properties()) if (!expected.contains(entry.getKey())) throw invalid("请求包含未知字段。");
    }
    private static void integer(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid("修订及来源代次须为整数。");
    }
    private static void textual(JsonNode value) {
        if (value == null || !value.isTextual()) throw invalid("标识、枚举与理由须为字符串。");
    }
    private static void noQuery(MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw invalid("当前依据及确认接口不接受查询参数。");
    }
    private static <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
