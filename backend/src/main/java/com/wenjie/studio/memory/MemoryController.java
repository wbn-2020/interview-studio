package com.wenjie.studio.memory;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import static com.wenjie.studio.memory.MemoryDtos.*;

@RestController
@RequestMapping(value = "/api/v1/memory", produces = MediaType.APPLICATION_JSON_VALUE)
public class MemoryController {
    private final MemoryService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;

    public MemoryController(MemoryService service, CurrentOwner owner) {
        this.service = service;
        this.owner = owner;
        // 直接解析有上限的原始字节；转为 JsonNode 后才检查重复键会丢失被覆盖字段。
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                                .maxStringLength(MemoryRules.MAX_REQUEST_BYTES).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/cards")
    public ResponseEntity<Page> list(@RequestParam(name = "status", defaultValue = "ACTIVE") Status status,
                                     @RequestParam(name = "kind", required = false) Kind kind,
                                     @RequestParam(name = "query", required = false) String query,
                                     @RequestParam(name = "sourceItemId", required = false) String sourceItemId,
                                     @RequestParam(name = "relatedConceptId", required = false) String relatedConceptId,
                                     @RequestParam(name = "cursor", required = false) String cursor,
                                     @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return response(service.list(owner.id(), status, kind, query, sourceItemId, relatedConceptId, cursor, limit));
    }

    @PostMapping(value = "/cards", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Card> create(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        Card card = service.create(owner.id(), key, MemoryRules.decode(requests, raw, Create.class));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(card);
    }

    @GetMapping("/cards/{id}")
    public ResponseEntity<Card> detail(@PathVariable("id") String id) { return response(service.detail(owner.id(), id)); }

    @PutMapping(value = "/cards/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Card> save(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                     @RequestBody byte[] raw) {
        return response(service.save(owner.id(), id, key, MemoryRules.decode(requests, raw, Save.class)));
    }

    @PostMapping(value = "/cards/{id}/archive", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Card> archive(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                        @RequestBody byte[] raw) {
        return response(service.status(owner.id(), id, key, MemoryRules.decode(requests, raw, ChangeStatus.class), Status.ARCHIVED));
    }

    @PostMapping(value = "/cards/{id}/restore", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Card> restore(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                        @RequestBody byte[] raw) {
        return response(service.status(owner.id(), id, key, MemoryRules.decode(requests, raw, ChangeStatus.class), Status.ACTIVE));
    }

    @GetMapping("/cards/{id}/versions")
    public ResponseEntity<VersionPage> versions(@PathVariable("id") String id,
                                               @RequestParam(name = "before", required = false) Integer before,
                                               @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return response(service.versions(owner.id(), id, before, limit));
    }

    @GetMapping("/cards/{id}/versions/{versionId}")
    public ResponseEntity<VersionDetail> version(@PathVariable("id") String id, @PathVariable("versionId") String versionId) {
        return response(service.version(owner.id(), id, versionId));
    }

    @PostMapping(value = "/cards/{id}/versions/{versionId}/reference", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Reference> reference(@PathVariable("id") String id, @PathVariable("versionId") String versionId,
                                               @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(service.reference(owner.id(), id, versionId, key, MemoryRules.decode(requests, raw, EmptyRequest.class)));
    }

    private <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
