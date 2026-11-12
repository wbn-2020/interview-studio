package com.wenjie.studio.review;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import static com.wenjie.studio.review.RepracticeDtos.*;

@RestController
@RequestMapping("/api/v1/review")
public class RepracticeController {
    private final RepracticeService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;

    public RepracticeController(RepracticeService service, CurrentOwner owner, JsonMapper json) {
        this.service = service;
        this.owner = owner;
        requests = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/repractices")
    public Page list(@RequestParam(name = "cursor", required = false) String cursor,
                     @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return service.list(owner.id(), cursor, limit);
    }

    @PostMapping("/repractices")
    @ResponseStatus(HttpStatus.CREATED)
    public Repractice create(@RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) {
        return service.create(owner.id(), key, RepracticeRules.decode(requests, body, Create.class));
    }

    @PostMapping("/recollections/{id}/repractices")
    @ResponseStatus(HttpStatus.CREATED)
    public Repractice createRecollection(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                         @RequestBody JsonNode body) {
        return service.createRecollection(owner.id(), id, key, RepracticeRules.decode(requests, body, CreateRecollection.class));
    }

    @GetMapping("/repractices/{id}")
    public Repractice detail(@PathVariable("id") String id) { return service.detail(owner.id(), id); }

    @PostMapping("/repractices/{id}/comparisons")
    @ResponseStatus(HttpStatus.CREATED)
    public ComparisonSummary compare(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                      @RequestBody JsonNode body) {
        return service.compare(owner.id(), id, key, RepracticeRules.decode(requests, body, Compare.class));
    }

    @GetMapping("/comparisons/{id}")
    public Comparison comparison(@PathVariable("id") String id) { return service.comparison(owner.id(), id); }

    @PostMapping("/comparisons/{id}/sources/{sourceId}/judgments")
    @ResponseStatus(HttpStatus.CREATED)
    public Judgment judge(@PathVariable("id") String id, @PathVariable("sourceId") String sourceId,
                           @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) {
        return service.judge(owner.id(), id, sourceId, key, RepracticeRules.decode(requests, body, Judge.class));
    }

    @GetMapping("/sources/{sourceId}/judgments")
    public JudgmentPage judgments(@PathVariable("sourceId") String sourceId,
                                   @RequestParam(name = "before", required = false) Integer before,
                                   @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return service.judgments(owner.id(), sourceId, before, limit);
    }

    @GetMapping("/actions/{id}/progress")
    public ProgressPage progress(@PathVariable("id") String id) { return service.progress(owner.id(), id); }
}
