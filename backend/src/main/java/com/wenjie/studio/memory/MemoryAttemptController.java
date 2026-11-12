package com.wenjie.studio.memory;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.wenjie.studio.memory.MemoryAttemptDtos.*;

@RestController
@RequestMapping(value = "/api/v1/memory", produces = MediaType.APPLICATION_JSON_VALUE)
public class MemoryAttemptController {
    private final MemoryAttemptService attempts;
    private final MemoryQueueService queue;
    private final MemoryCramService cram;
    private final CurrentOwner owner;

    public MemoryAttemptController(MemoryAttemptService attempts, MemoryQueueService queue, MemoryCramService cram, CurrentOwner owner) {
        this.attempts = attempts; this.queue = queue; this.cram = cram; this.owner = owner;
    }

    @PostMapping(value = "/cards/{id}/attempts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AttemptDetail> start(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return created(attempts.start(owner.id(), id, key, decode(raw, StartAttempt.class)));
    }
    @GetMapping("/cards/{id}/active-attempt")
    public ResponseEntity<ActiveAttempt> active(@PathVariable("id") String id) { return response(attempts.active(owner.id(), id)); }

    @GetMapping("/attempts/open")
    public ResponseEntity<OpenAttemptPage> open(@RequestParam(name = "cursor", required = false) String cursor,
                                                @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return response(attempts.openAttempts(owner.id(), cursor, limit));
    }
    @GetMapping("/attempts/{id}")
    public ResponseEntity<AttemptDetail> detail(@PathVariable("id") String id) { return response(attempts.detail(owner.id(), id)); }

    @PutMapping(value = "/attempts/{id}/response", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Attempt> save(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(attempts.saveResponse(owner.id(), id, key, decode(raw, SaveResponse.class)));
    }
    @PostMapping(value = "/attempts/{id}/reveal", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RevealedAttempt> reveal(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(attempts.reveal(owner.id(), id, key, decode(raw, Reveal.class)));
    }
    @GetMapping("/attempts/{id}/reference")
    public ResponseEntity<MemoryDtos.Reference> reference(@PathVariable("id") String id) { return response(attempts.reference(owner.id(), id)); }

    @PostMapping(value = "/attempts/{id}/rate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RatedAttempt> rate(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(attempts.rate(owner.id(), id, key, decode(raw, Rate.class)));
    }
    @PostMapping(value = "/attempts/{id}/skip", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Attempt> skip(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(attempts.skip(owner.id(), id, key, decode(raw, Skip.class)));
    }
    @GetMapping("/cards/{id}/attempts")
    public ResponseEntity<AttemptPage> history(@PathVariable("id") String id,
                                               @RequestParam(name = "before", required = false) Long before,
                                               @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return response(attempts.history(owner.id(), id, before, limit));
    }
    @GetMapping("/attempts/{id}/events")
    public ResponseEntity<EventPage> events(@PathVariable("id") String id,
                                            @RequestParam(name = "before", required = false) Long before,
                                            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return response(attempts.events(owner.id(), id, before, limit));
    }
    @GetMapping("/review-queue")
    public ResponseEntity<ReviewQueue> queue(@RequestParam(name = "cursor", required = false) String cursor,
                                             @RequestParam(name = "limit", defaultValue = "20") int limit,
                                             @RequestParam(name = "includeOverBudget", defaultValue = "false") String includeOverBudget) {
        if (!"true".equals(includeOverBudget) && !"false".equals(includeOverBudget)) throw MemoryReviewRules.invalid("超预算筛选须为 true 或 false。");
        return response(queue.queue(owner.id(), cursor, limit, Boolean.parseBoolean(includeOverBudget)));
    }
    @PostMapping(value = "/cram-batches", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CramBatch> createBatch(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return created(cram.create(owner.id(), key, decode(raw, CreateCramBatch.class)));
    }
    @GetMapping("/cram-batches")
    public ResponseEntity<CramBatchPage> batches(@RequestParam(name = "cursor", required = false) String cursor,
                                                 @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return response(cram.list(owner.id(), cursor, limit));
    }
    @GetMapping("/cram-batches/{id}")
    public ResponseEntity<CramBatch> batch(@PathVariable("id") String id) { return response(cram.detail(owner.id(), id)); }

    @GetMapping("/cram-batches/{id}/cards")
    public ResponseEntity<CramMemberPage> members(@PathVariable("id") String id,
                                                  @RequestParam(name = "after", required = false) Integer after,
                                                  @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return response(cram.members(owner.id(), id, after, limit));
    }

    private <T> T decode(byte[] raw, Class<T> type) { return MemoryAttemptRules.decode(raw, type); }
    private <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    private <T> ResponseEntity<T> created(T value) { return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(value); }
}
