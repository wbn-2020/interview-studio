package com.wenjie.studio.review;

import com.wenjie.studio.ai.AiDtos.EvaluationInput;
import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import static com.wenjie.studio.review.ReviewDtos.*;

@RestController
@RequestMapping("/api/v1/review")
public class ReviewController {
    private final ReviewService review;
    private final CurrentOwner owner;
    private final JsonMapper requests;

    public ReviewController(ReviewService review, CurrentOwner owner, JsonMapper json) {
        this.review = review;
        this.owner = owner;
        requests = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/context")
    public EvaluationInput context(@RequestParam("sessionId") String sessionId,
                                   @RequestParam("questionId") String questionId,
                                   @RequestParam("answerId") String answerId) {
        return review.context(owner.id(), new AnswerLink(sessionId, questionId, answerId));
    }

    @GetMapping("/feedback")
    public FeedbackPage feedbacks(@RequestParam("sessionId") String sessionId,
                                 @RequestParam("questionId") String questionId,
                                 @RequestParam("answerId") String answerId,
                                 @RequestParam(name = "cursor", required = false) String cursor,
                                 @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return review.feedbacks(owner.id(), new AnswerLink(sessionId, questionId, answerId), cursor, limit);
    }

    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public Feedback createFeedback(@RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) {
        return review.createFeedback(owner.id(), key, ReviewRules.decode(requests, body, CreateFeedback.class));
    }

    @GetMapping("/feedback/{id}")
    public Feedback feedback(@PathVariable("id") String id,
                             @RequestParam(name = "revision", required = false) Integer revision) {
        return review.feedback(owner.id(), id, revision);
    }

    @PutMapping("/feedback/{id}")
    public Feedback saveFeedback(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                 @RequestBody JsonNode body) {
        return review.saveFeedback(owner.id(), id, key, ReviewRules.decode(requests, body, SaveFeedback.class));
    }

    @GetMapping("/feedback/{id}/history")
    public FeedbackHistoryPage feedbackHistory(@PathVariable("id") String id,
                                               @RequestParam(name = "before", required = false) Integer before,
                                               @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return review.feedbackHistory(owner.id(), id, before, limit);
    }

    @PostMapping("/feedback/{id}/decisions")
    public Feedback decide(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                           @RequestBody JsonNode body) {
        return review.decideFeedback(owner.id(), id, key, ReviewRules.decode(requests, body, DecideFeedback.class));
    }

    @GetMapping("/actions")
    public ActionPage actions(@RequestParam(name = "status", required = false) ActionStatus status,
                              @RequestParam(name = "cursor", required = false) String cursor,
                              @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return review.actions(owner.id(), status, cursor, limit);
    }

    @PostMapping("/actions")
    @ResponseStatus(HttpStatus.CREATED)
    public Action createAction(@RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) {
        return review.createAction(owner.id(), key, ReviewRules.decode(requests, body, CreateAction.class));
    }

    @PostMapping("/recollections/{id}/actions")
    @ResponseStatus(HttpStatus.CREATED)
    public Action createRecollectionAction(@PathVariable("id") String id,
                                           @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) {
        return review.createRecollectionAction(owner.id(), id, key, ReviewRules.decode(requests, body, CreateRecollectionAction.class));
    }

    @GetMapping("/actions/{id}")
    public Action action(@PathVariable("id") String id) { return review.action(owner.id(), id); }

    @PutMapping("/actions/{id}")
    public Action saveAction(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                             @RequestBody JsonNode body) {
        return review.saveAction(owner.id(), id, key, ReviewRules.decode(requests, body, SaveAction.class));
    }

    @PutMapping("/actions/{id}/sources/{sourceId}")
    public Action saveSource(@PathVariable("id") String id, @PathVariable("sourceId") String sourceId,
                             @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) {
        return review.saveSource(owner.id(), id, sourceId, key, ReviewRules.decode(requests, body, SaveSource.class));
    }

    @PostMapping("/actions/{id}/merge")
    public Action merge(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                        @RequestBody JsonNode body) {
        return review.merge(owner.id(), id, key, ReviewRules.decode(requests, body, MergeAction.class));
    }

    @GetMapping("/actions/{id}/history")
    public ActionHistoryPage actionHistory(@PathVariable("id") String id,
                                           @RequestParam(name = "before", required = false) Long before,
                                           @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return review.actionHistory(owner.id(), id, before, limit);
    }
}
