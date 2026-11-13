package com.wenjie.studio.bank;

import com.wenjie.studio.bank.QuestionDtos.*;
import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/bank")
public class QuestionController {
    private final QuestionService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;

    public QuestionController(QuestionService service, CurrentOwner owner, JsonMapper json) {
        this.service = service;
        this.owner = owner;
        requests = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/questions")
    public QuestionPage questions(@RequestParam(name = "status", defaultValue = "ACTIVE") Status status,
                                   @RequestParam(name = "q", required = false) String query,
                                   @RequestParam(name = "type", required = false) Type type,
                                   @RequestParam(name = "difficulty", required = false) Integer difficulty,
                                   @RequestParam(name = "dimension", required = false) Dimension dimension,
                                   @RequestParam(name = "topicId", required = false) String topicId,
                                   @RequestParam(name = "knowledgeId", required = false) String knowledgeId,
                                   @RequestParam(name = "scene", required = false) String scene,
                                   @RequestParam(name = "favorite", required = false) Boolean favorite,
                                   @RequestParam(name = "cursor", required = false) String cursor,
                                   @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.list(owner.id(), status, query, type, difficulty, dimension, topicId, knowledgeId,
                scene, favorite, cursor, limit);
    }

    @PostMapping("/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public Question create(@RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        return service.create(owner.id(), key, decode(request, QuestionInput.class));
    }

    @GetMapping("/questions/{id}")
    public Question question(@PathVariable("id") String id) { return service.detail(owner.id(), id); }

    @PutMapping("/questions/{id}")
    public Question save(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                         @RequestBody JsonNode request) {
        return service.save(owner.id(), id, key, decode(request, SaveQuestion.class));
    }

    @PostMapping("/questions/{id}/archive")
    public Question archive(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                            @RequestBody JsonNode request) {
        return service.status(owner.id(), id, key, decode(request, ChangeStatus.class), Status.ARCHIVED);
    }

    @PostMapping("/questions/{id}/restore")
    public Question restore(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                            @RequestBody JsonNode request) {
        return service.status(owner.id(), id, key, decode(request, ChangeStatus.class), Status.ACTIVE);
    }

    @PutMapping("/questions/{id}/favorite")
    public Question favorite(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                             @RequestBody JsonNode request) {
        return service.favorite(owner.id(), id, key, decode(request, SetFavorite.class));
    }

    @GetMapping("/questions/{id}/versions")
    public VersionPage versions(@PathVariable("id") String id,
                                @RequestParam(name = "before", required = false) Integer before,
                                @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.versions(owner.id(), id, before, limit);
    }

    @GetMapping("/questions/{id}/versions/{versionId}")
    public QuestionVersion version(@PathVariable("id") String id, @PathVariable("versionId") String versionId) {
        return service.version(owner.id(), id, versionId);
    }

    @GetMapping("/questions/{id}/versions/{versionId}/knowledge-references")
    public ResponseEntity<KnowledgeReferencePage> knowledgeReferences(@PathVariable("id") String id,
            @PathVariable("versionId") String versionId, @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw QuestionGuards.invalid("题目知识来源核对不接受查询参数。");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.knowledgeReferences(owner.id(), id, versionId));
    }

    @PostMapping("/questions/{id}/versions/{versionId}/reference")
    public Reference reference(@PathVariable("id") String id, @PathVariable("versionId") String versionId,
                               @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        return service.reference(owner.id(), id, versionId, key, decode(request, EmptyRequest.class));
    }

    @GetMapping("/questions/{id}/exposures")
    public ExposurePage exposures(@PathVariable("id") String id,
                                  @RequestParam(name = "before", required = false) String before,
                                  @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.exposures(owner.id(), id, before, limit);
    }

    private <T> T decode(JsonNode body, Class<T> type) {
        if (body == null || !body.isObject()) throw QuestionGuards.invalid("请求必须为 JSON 对象。");
        rejectTextCoercion(body);
        try { return requests.treeToValue(body, type); }
        catch (RuntimeException exception) { throw QuestionGuards.invalid("请求字段或类型不符合题库接口要求。"); }
    }

    private void rejectTextCoercion(JsonNode node) {
        // 通用标量开关不覆盖所有 String 转换，文本叶子另行检查，保留用户原文及明确的字段类型。
        for (var field : node.properties()) {
            JsonNode value = field.getValue();
            if (value == null || value.isNull()) continue;
            String name = field.getKey();
            if ("expectedRevision".equals(name) || "difficulty".equals(name)) {
                if (!value.isIntegralNumber()) throw QuestionGuards.invalid("修订号和难度必须为整数。");
            } else if ("favorite".equals(name)) {
                if (!value.isBoolean()) throw QuestionGuards.invalid("收藏状态必须为布尔值。");
            } else if (value.isObject()) {
                rejectTextCoercion(value);
            } else if (value.isArray()) {
                for (JsonNode entry : value) {
                    if (entry.isObject()) rejectTextCoercion(entry);
                    else if (!entry.isTextual()) throw QuestionGuards.invalid("列表内容类型不符合要求。");
                }
            } else if (!value.isTextual()) {
                throw QuestionGuards.invalid("文本字段必须为字符串。");
            }
        }
    }
}
