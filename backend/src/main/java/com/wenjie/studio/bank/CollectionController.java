package com.wenjie.studio.bank;

import com.wenjie.studio.bank.CollectionDtos.*;
import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.HttpStatus;
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
public class CollectionController {
    private final CollectionService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;

    public CollectionController(CollectionService service, CurrentOwner owner, JsonMapper json) {
        this.service = service;
        this.owner = owner;
        requests = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/collections")
    public CollectionPage collections(@RequestParam(name = "kind", required = false) CollectionKind kind,
                                       @RequestParam(name = "status", defaultValue = "ACTIVE") Status status,
                                       @RequestParam(name = "q", required = false) String query,
                                       @RequestParam(name = "cursor", required = false) String cursor,
                                       @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.list(owner.id(), kind, status, query, cursor, limit);
    }

    @PostMapping("/collections")
    @ResponseStatus(HttpStatus.CREATED)
    public Collection create(@RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        return service.create(owner.id(), key, decode(request, CreateCollection.class));
    }

    @GetMapping("/collections/{id}")
    public Collection collection(@PathVariable("id") String id) { return service.detail(owner.id(), id); }

    @PutMapping("/collections/{id}")
    public Collection save(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                           @RequestBody JsonNode request) {
        return service.save(owner.id(), id, key, decode(request, SaveCollection.class));
    }

    @PostMapping("/collections/{id}/archive")
    public Collection archive(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                              @RequestBody JsonNode request) {
        return service.status(owner.id(), id, key, decode(request, ChangeStatus.class), Status.ARCHIVED);
    }

    @PostMapping("/collections/{id}/restore")
    public Collection restore(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                              @RequestBody JsonNode request) {
        return service.status(owner.id(), id, key, decode(request, ChangeStatus.class), Status.ACTIVE);
    }

    @GetMapping("/collections/{id}/versions")
    public VersionPage versions(@PathVariable("id") String id,
                                @RequestParam(name = "before", required = false) Integer before,
                                @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.versions(owner.id(), id, before, limit);
    }

    @GetMapping("/collections/{id}/versions/{versionId}")
    public CollectionVersion version(@PathVariable("id") String id, @PathVariable("versionId") String versionId) {
        return service.version(owner.id(), id, versionId);
    }

    private <T> T decode(JsonNode body, Class<T> type) {
        if (body == null || !body.isObject()) throw QuestionGuards.invalid("请求必须为 JSON 对象。");
        rejectTextCoercion(body);
        try { return requests.treeToValue(body, type); }
        catch (RuntimeException exception) { throw QuestionGuards.invalid("请求字段或类型不符合题单接口要求。"); }
    }

    private void rejectTextCoercion(JsonNode node) {
        // String 的数字／布尔转换不受通用标量开关完整约束，不能把错误类型保存成用户原文。
        for (var field : node.properties()) {
            JsonNode value = field.getValue();
            if (value == null || value.isNull()) continue;
            if ("expectedRevision".equals(field.getKey())) {
                if (!value.isIntegralNumber()) throw QuestionGuards.invalid("修订号必须为整数。");
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
