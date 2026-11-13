package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static com.wenjie.studio.knowledge.KnowledgeGuards.invalid;
import static com.wenjie.studio.knowledge.KnowledgePackageDtos.*;

@RestController
@RequestMapping(value = "/api/v1/knowledge/packages", produces = MediaType.APPLICATION_JSON_VALUE)
public class KnowledgePackageController {
    private static final Set<String> ITEM_FIELDS = Set.of("sourceId", "source", "title", "body", "kind",
            "decision", "tags", "aliases", "topicIds");
    private static final Set<String> ATTACHMENT_FIELDS = Set.of("sourceId", "attachmentId", "sourceSha256");
    private final CurrentOwner owner;
    private final KnowledgePackageService service;
    private final JsonMapper requests;

    public KnowledgePackageController(CurrentOwner owner, KnowledgePackageService service) {
        this.owner = owner; this.service = service;
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(5)
                                .maxStringLength(100000).maxNumberLength(16).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Preview> preview(@RequestParam MultiValueMap<String, String> query, @RequestBody byte[] raw) {
        JsonNode body = plan(raw, query, Set.of("items", "sourceAttachments"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.preview(owner.id(), convert(body, Plan.class)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Detail> register(@RequestHeader("Idempotency-Key") String key,
                                           @RequestParam MultiValueMap<String, String> query, @RequestBody byte[] raw) {
        JsonNode body = plan(raw, query, Set.of("packageKey", "createCopy", "items", "sourceAttachments"));
        if (!body.get("packageKey").isTextual() || !body.get("createCopy").isBoolean()) {
            throw invalid("知识包标识和副本选择类型无效。");
        }
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(service.register(owner.id(), key, convert(body, Register.class)));
    }

    @GetMapping
    public ResponseEntity<Page> list(@RequestParam MultiValueMap<String, String> query) {
        if (query.entrySet().stream().anyMatch(entry -> !Set.of("cursor", "limit").contains(entry.getKey())
                || entry.getValue().size() != 1)) throw invalid("知识包查询参数无效或重复。");
        String limit = query.getFirst("limit");
        if (limit != null && !limit.matches("(?:[1-9]|[1-4][0-9]|50)")) throw invalid("每页数量须为1至50。");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(owner.id(), query.getFirst("cursor"), limit == null ? 20 : Integer.parseInt(limit)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Detail> detail(@PathVariable String id, @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw invalid("读取知识包详情不接受查询参数。");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(owner.id(), id));
    }

    @PostMapping(value = "/{id}/commit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Detail> commit(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                         @RequestParam MultiValueMap<String, String> query, @RequestBody byte[] raw) {
        JsonNode body = plan(raw, query, Set.of("expectedRevision", "expectedPlanSha256", "items", "sourceAttachments"));
        if (!body.get("expectedRevision").isIntegralNumber() || !body.get("expectedRevision").canConvertToLong()
                || !body.get("expectedPlanSha256").isTextual()) throw invalid("知识包修订号和计划摘要类型无效。");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.commit(owner.id(), key, id, convert(body, Commit.class)));
    }

    private JsonNode plan(byte[] raw, MultiValueMap<String, String> query, Set<String> expected) {
        if (!query.isEmpty() || raw == null || raw.length == 0 || raw.length > 1_048_576) {
            throw invalid("知识包请求不能带查询参数，JSON总量须在1MiB以内；请缩小本次明确选择的文件范围。");
        }
        final JsonNode root;
        try { root = requests.readTree(raw); }
        catch (RuntimeException exception) { throw invalid("知识包请求JSON、重复字段或嵌套结构无效。"); }
        fields(root, expected);
        JsonNode items = root.get("items"), attachments = root.get("sourceAttachments");
        if (!items.isArray() || items.isEmpty() || items.size() > 20
                || !attachments.isArray() || attachments.size() > 20) throw invalid("知识包须包含1至20项文本及至多20项已上传附件映射。");
        for (JsonNode item : items) {
            fields(item, ITEM_FIELDS);
            for (String field : Set.of("sourceId", "source", "title", "body", "kind", "decision")) {
                if (!item.get(field).isTextual()) throw invalid("知识包文本、类型和决定字段须为字符串。");
            }
            for (String field : Set.of("tags", "aliases", "topicIds")) {
                if (!item.get(field).isArray() || item.get(field).size() > 20) throw invalid("标签、别名和主题须为最多20项的明确数组。");
                for (JsonNode value : item.get(field)) if (!value.isTextual()) throw invalid("标签、别名和主题各项须为字符串。");
            }
        }
        for (JsonNode attachment : attachments) {
            fields(attachment, ATTACHMENT_FIELDS);
            for (String field : ATTACHMENT_FIELDS) if (!attachment.get(field).isTextual()) {
                throw invalid("附件映射字段须为字符串。");
            }
        }
        return root;
    }

    private static void fields(JsonNode body, Set<String> expected) {
        if (body == null || !body.isObject() || body.size() != expected.size()
                || expected.stream().anyMatch(field -> !body.has(field))) throw invalid("知识包请求存在未知或缺失字段。");
    }

    private <T> T convert(JsonNode body, Class<T> type) {
        try { return requests.treeToValue(body, type); }
        catch (RuntimeException exception) { throw invalid("知识包字段类型或枚举值无效。"); }
    }
}
