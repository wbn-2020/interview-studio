package com.wenjie.studio.review;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static com.wenjie.studio.review.StandardReviewDtos.*;
import static com.wenjie.studio.review.StandardReviewRules.*;

@RestController
@RequestMapping(value = "/api/v1/bank/standard-reviews", produces = MediaType.APPLICATION_JSON_VALUE)
public class StandardReviewController {
    private final StandardReviewService reviews;
    private final CurrentOwner owner;

    public StandardReviewController(StandardReviewService reviews, CurrentOwner owner) {
        this.reviews = reviews; this.owner = owner;
    }

    @GetMapping
    public ResponseEntity<StandardReviewPage> list(@RequestParam MultiValueMap<String, String> query) {
        parameters(query, Set.of("questionId", "versionId", "status", "cursor", "limit"));
        var question = question(new QuestionVersionRef(query.getFirst("questionId"), query.getFirst("versionId")));
        var selectedStatus = query.containsKey("status") ? status(query.getFirst("status")) : null;
        return response(reviews.list(owner.id(), question, selectedStatus, query.getFirst("cursor"), limit(query)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<StandardReviewDetail> detail(@PathVariable String id, @RequestParam MultiValueMap<String, String> query) {
        parameters(query, Set.of("revision"));
        return response(reviews.get(owner.id(), id, number(query, "revision")));
    }

    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StandardReviewPreview> preview(@RequestBody byte[] raw) {
        return response(reviews.preview(owner.id(), decode(raw, StandardReviewDraft.class)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StandardReviewSnapshot> create(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(reviews.create(owner.id(), key, decode(raw, SaveStandardReview.class)));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StandardReviewSnapshot> save(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                                     @RequestBody byte[] raw) {
        return response(reviews.save(owner.id(), id, key, decode(raw, SaveStandardReview.class)));
    }

    @PostMapping(value = "/{id}/withdraw", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StandardRevisionSummary> withdraw(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                                          @RequestBody byte[] raw) {
        return response(reviews.withdraw(owner.id(), id, key, decode(raw, WithdrawRecord.class)));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<StandardHistoryPage> history(@PathVariable String id, @RequestParam MultiValueMap<String, String> query) {
        parameters(query, Set.of("before", "limit"));
        return response(reviews.history(owner.id(), id, number(query, "before"), limit(query)));
    }

    @PostMapping(value = "/{id}/open", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OpenedStandardReview> open(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                                   @RequestBody byte[] raw) {
        return response(reviews.open(owner.id(), id, key, decode(raw, OpenStandardReview.class)));
    }

    private static void parameters(MultiValueMap<String, String> query, Set<String> allowed) {
        for (var entry : query.entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().size() != 1
                    || entry.getValue().getFirst() == null || entry.getValue().getFirst().isEmpty()) {
                throw invalid("查询参数未定义、重复或为空。");
            }
        }
    }

    private static Long number(MultiValueMap<String, String> query, String key) {
        String value = query.getFirst(key);
        if (value == null) return null;
        if (!value.matches("0|[1-9][0-9]{0,15}")) throw invalid("查询修订须为非负安全整数。");
        try { return revision(Long.parseLong(value)); }
        catch (NumberFormatException exception) { throw invalid("查询修订须为非负安全整数。"); }
    }

    private static int limit(MultiValueMap<String, String> query) {
        Long value = number(query, "limit");
        if (value == null) return 20;
        if (value < 1 || value > 50) throw invalid("每页数量须为1至50。");
        return value.intValue();
    }

    private static <T> ResponseEntity<T> response(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
