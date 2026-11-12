package com.wenjie.studio.review;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import static com.wenjie.studio.review.RecollectionDtos.*;

@RestController
@RequestMapping("/api/v1")
public class RecollectionController {
    private final RecollectionService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;

    public RecollectionController(RecollectionService service, CurrentOwner owner, JsonMapper json) {
        this.service = service;
        this.owner = owner;
        requests = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/review/recollections")
    public Page list(@RequestParam(name = "status", defaultValue = "ACTIVE") Status status,
                     @RequestParam(name = "interviewId", required = false) String interviewId,
                     @RequestParam(name = "query", required = false) String query,
                     @RequestParam(name = "cursor", required = false) String cursor,
                     @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.list(owner.id(), status, interviewId, query, cursor, limit);
    }

    @PostMapping("/review/recollections")
    @ResponseStatus(HttpStatus.CREATED)
    public Recollection create(@RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) {
        return service.create(owner.id(), key, RecollectionRules.decode(requests, body, Create.class));
    }

    @GetMapping("/review/recollections/{id}")
    public Recollection detail(@PathVariable("id") String id) { return service.detail(owner.id(), id); }

    @PutMapping("/review/recollections/{id}")
    public Recollection save(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                            @RequestBody JsonNode body) {
        return service.save(owner.id(), id, key, RecollectionRules.decode(requests, body, Save.class));
    }

    @GetMapping("/review/recollections/{id}/versions")
    public VersionPage versions(@PathVariable("id") String id,
                                @RequestParam(name = "before", required = false) Integer before,
                                @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return service.versions(owner.id(), id, before, limit);
    }

    @GetMapping("/review/recollections/{id}/versions/{versionId}")
    public Version version(@PathVariable("id") String id, @PathVariable("versionId") String versionId) {
        return service.version(owner.id(), id, versionId);
    }

    @PostMapping("/review/recollections/{id}/archive")
    public Recollection archive(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                               @RequestBody JsonNode body) {
        return service.status(owner.id(), id, key, RecollectionRules.decode(requests, body, ChangeStatus.class), Status.ARCHIVED);
    }

    @PostMapping("/review/recollections/{id}/restore")
    public Recollection restore(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                               @RequestBody JsonNode body) {
        return service.status(owner.id(), id, key, RecollectionRules.decode(requests, body, ChangeStatus.class), Status.ACTIVE);
    }

    @PostMapping("/review/recollections/{id}/question")
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionAdoption createQuestion(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                          @RequestBody JsonNode body) {
        return service.createQuestion(owner.id(), id, key, RecollectionRules.decode(requests, body, CreateQuestion.class));
    }

    @GetMapping("/review/recollections/{id}/versions/{versionId}/question")
    public QuestionLookup question(@PathVariable("id") String id, @PathVariable("versionId") String versionId) {
        return service.question(owner.id(), id, versionId);
    }

    @GetMapping("/bank/questions/{id}/versions/{versionId}/recollection-source")
    public QuestionOrigin questionSource(@PathVariable("id") String id, @PathVariable("versionId") String versionId) {
        return service.questionSource(owner.id(), id, versionId);
    }
}
