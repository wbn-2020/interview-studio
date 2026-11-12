package com.wenjie.studio.training;

import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.CurrentOwner;
import com.wenjie.studio.training.TrainingDtos.*;
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

import java.util.Set;

@RestController
@RequestMapping("/api/v1/training")
public class TrainingController {
    private static final Set<String> INTEGERS = Set.of("expectedRevision", "expectedSessionRevision", "expectedBoardRevision",
            "revision", "timeBudgetMinutes", "followUpLimit");
    private final TrainingService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;

    public TrainingController(TrainingService service, CurrentOwner owner, JsonMapper json) {
        this.service = service;
        this.owner = owner;
        requests = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping("/sessions")
    public SessionPage sessions(@RequestParam(name = "status", required = false) Status status,
                                 @RequestParam(name = "cursor", required = false) String cursor,
                                 @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.list(owner.id(), status, cursor, limit);
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public Session create(@RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        return service.create(owner.id(), key, decode(request, CreateSession.class));
    }

    @GetMapping("/active")
    public Active active() { return service.active(owner.id()); }

    @GetMapping("/sessions/{id}")
    public Session session(@PathVariable("id") String id) { return service.detail(owner.id(), id); }

    @PostMapping("/sessions/{id}/start")
    public Session start(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                          @RequestBody JsonNode request) {
        return service.start(owner.id(), id, key, decode(request, ChangeState.class));
    }

    @PostMapping("/sessions/{id}/pause")
    public Session pause(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                          @RequestBody JsonNode request) {
        return service.pause(owner.id(), id, key, decode(request, ChangeState.class));
    }

    @PostMapping("/sessions/{id}/resume")
    public Session resume(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                           @RequestBody JsonNode request) {
        return service.resume(owner.id(), id, key, decode(request, ChangeState.class));
    }

    @PostMapping("/sessions/{id}/end")
    public Session end(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                        @RequestBody JsonNode request) {
        return service.end(owner.id(), id, key, decode(request, EndSession.class));
    }

    @PostMapping("/sessions/{id}/pulse")
    public Timing pulse(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                         @RequestBody JsonNode request) {
        return service.pulse(owner.id(), id, key, decode(request, EmptyRequest.class));
    }

    @PostMapping("/sessions/{id}/assistance")
    public Session assistance(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                               @RequestBody JsonNode request) {
        return service.assistance(owner.id(), id, key, decode(request, Assistance.class));
    }

    @GetMapping("/sessions/{sessionId}/events")
    public EventPage events(@PathVariable("sessionId") String id,
                            @RequestParam(name = "before", required = false) Integer before,
                            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.events(owner.id(), id, before, limit);
    }

    @GetMapping("/sessions/{sessionId}/questions/{questionId}")
    public Question question(@PathVariable("sessionId") String sessionId, @PathVariable("questionId") String questionId) {
        return service.question(owner.id(), sessionId, questionId);
    }

    @PutMapping("/sessions/{sessionId}/questions/{questionId}/draft")
    public Question draft(@PathVariable("sessionId") String sessionId, @PathVariable("questionId") String questionId,
                           @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        return service.draft(owner.id(), sessionId, questionId, key, decode(request, SaveAnswer.class));
    }

    @PostMapping("/sessions/{sessionId}/questions/{questionId}/submit")
    public Question submit(@PathVariable("sessionId") String sessionId, @PathVariable("questionId") String questionId,
                            @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        return service.submit(owner.id(), sessionId, questionId, key, decode(request, SaveAnswer.class));
    }

    @PostMapping("/sessions/{sessionId}/questions/{questionId}/skip")
    public Question skip(@PathVariable("sessionId") String sessionId, @PathVariable("questionId") String questionId,
                          @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        return service.skip(owner.id(), sessionId, questionId, key, decode(request, QuestionCommand.class), true);
    }

    @PostMapping("/sessions/{sessionId}/questions/{questionId}/unskip")
    public Question unskip(@PathVariable("sessionId") String sessionId, @PathVariable("questionId") String questionId,
                            @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        return service.skip(owner.id(), sessionId, questionId, key, decode(request, QuestionCommand.class), false);
    }

    @GetMapping("/sessions/{sessionId}/questions/{questionId}/answers")
    public AnswerPage answers(@PathVariable("sessionId") String sessionId, @PathVariable("questionId") String questionId,
                              @RequestParam(name = "before", required = false) Integer before,
                              @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.answers(owner.id(), sessionId, questionId, before, limit);
    }

    @GetMapping("/sessions/{sessionId}/questions/{questionId}/answers/{answerId}")
    public Answer answer(@PathVariable("sessionId") String sessionId, @PathVariable("questionId") String questionId,
                          @PathVariable("answerId") String answerId) {
        return service.answer(owner.id(), sessionId, questionId, answerId);
    }

    @PostMapping("/sessions/{sessionId}/questions/{questionId}/reference")
    public QuestionDtos.Reference reference(@PathVariable("sessionId") String sessionId,
            @PathVariable("questionId") String questionId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody JsonNode request) {
        return service.reference(owner.id(), sessionId, questionId, key, decode(request, EmptyRequest.class));
    }

    @PostMapping("/sessions/{sessionId}/import-board")
    public BoardImportCandidate importBoard(@PathVariable("sessionId") String sessionId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        return service.importBoard(owner.id(), sessionId, key, decode(request, ImportBoard.class));
    }

    private <T> T decode(JsonNode body, Class<T> type) {
        if (body == null || !body.isObject()) throw invalid();
        rejectCoercion(body);
        try { return requests.treeToValue(body, type); }
        catch (RuntimeException exception) { throw invalid(); }
    }

    private void rejectCoercion(JsonNode node) {
        // 文本、修订号和坐标分别校验，避免 String 的数字转换及坐标被截断后悄悄保存。
        for (var field : node.properties()) {
            JsonNode value = field.getValue();
            if (value == null || value.isNull()) continue;
            String name = field.getKey();
            if (INTEGERS.contains(name)) {
                if (!value.isIntegralNumber()) throw invalid();
            } else if ("x".equals(name) || "y".equals(name)) {
                if (!value.isNumber()) throw invalid();
            } else if ("checklistConfirmed".equals(name)) {
                if (!value.isBoolean()) throw invalid();
            } else if (value.isObject()) rejectCoercion(value);
            else if (value.isArray()) {
                for (JsonNode entry : value) {
                    if (entry.isObject()) rejectCoercion(entry);
                    else if (!entry.isTextual()) throw invalid();
                }
            } else if (!value.isTextual()) throw invalid();
        }
    }

    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "请求字段或类型不符合训练接口要求。"); }
}
