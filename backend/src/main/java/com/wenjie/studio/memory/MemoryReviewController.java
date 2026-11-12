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

import static com.wenjie.studio.memory.MemoryReviewDtos.*;

@RestController
@RequestMapping(value = "/api/v1/memory", produces = MediaType.APPLICATION_JSON_VALUE)
public class MemoryReviewController {
    private final MemoryReviewService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;

    public MemoryReviewController(MemoryReviewService service, CurrentOwner owner) {
        this.service = service; this.owner = owner;
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
                                .maxStringLength(MemoryReviewRules.REQUEST_BYTES).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/cards/{id}/recall-state")
    public ResponseEntity<RecallState> state(@PathVariable("id") String id) { return response(service.recallState(owner.id(), id)); }

    @GetMapping("/cards/{id}/impacts")
    public ResponseEntity<ImpactPage> impacts(@PathVariable("id") String id,
                                             @RequestParam(name = "before", required = false) Long before,
                                             @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return response(service.impacts(owner.id(), id, before, limit));
    }

    @PostMapping(value = "/cards/{id}/source-review", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RecallState> review(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(service.sourceReview(owner.id(), id, key, decode(raw, SourceReview.class)));
    }

    @PostMapping(value = "/cards/{id}/schedule", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Schedule> enable(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.enable(owner.id(), id, key, decode(raw, EnableSchedule.class)));
    }

    @PostMapping(value = "/cards/{id}/schedule/defer", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Schedule> defer(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(service.defer(owner.id(), id, key, decode(raw, DeferSchedule.class)));
    }

    @PostMapping(value = "/cards/{id}/schedule/pause", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Schedule> pause(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(service.pause(owner.id(), id, key, decode(raw, PauseSchedule.class)));
    }

    @PostMapping(value = "/cards/{id}/schedule/resume", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Schedule> resume(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(service.resume(owner.id(), id, key, decode(raw, ResumeSchedule.class)));
    }

    @GetMapping("/cards/{id}/schedule/history")
    public ResponseEntity<ScheduleHistoryPage> history(@PathVariable("id") String id,
                                                       @RequestParam(name = "before", required = false) Long before,
                                                       @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return response(service.scheduleHistory(owner.id(), id, before, limit));
    }

    @GetMapping("/review-settings")
    public ResponseEntity<Settings> settings() { return response(service.settings(owner.id())); }

    @PostMapping(value = "/review-settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Settings> createSettings(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.createSettings(owner.id(), key, decode(raw, CreateSettings.class)));
    }

    @PutMapping(value = "/review-settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Settings> saveSettings(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(service.saveSettings(owner.id(), key, decode(raw, SaveSettings.class)));
    }

    private <T> T decode(byte[] raw, Class<T> type) { return MemoryReviewRules.decode(requests, raw, type); }
    private <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
