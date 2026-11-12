package com.wenjie.studio.schedule;

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

import static com.wenjie.studio.schedule.SchedulePreparationDtos.*;
import static com.wenjie.studio.schedule.SchedulePreparationRules.*;

@RestController
@RequestMapping(value = "/api/v1/schedule/interviews/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
public class SchedulePreparationController {
    private final SchedulePreparationService service;
    private final CurrentOwner owner;

    public SchedulePreparationController(SchedulePreparationService service, CurrentOwner owner) { this.service = service; this.owner = owner; }

    @GetMapping("/preparation-links")
    public ResponseEntity<LinkPage> list(@PathVariable("id") String id, @RequestParam MultiValueMap<String, String> query) {
        parameters(query, Set.of("status", "cursor", "limit"));
        return response(service.list(owner.id(), id, enumValue(query.getFirst("status"), LinkStatus.class),
                query.getFirst("cursor"), pageSize(query.getFirst("limit"))));
    }

    @PostMapping(value = "/preparation-links", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreparationLink> create(@PathVariable("id") String id,
                                                   @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(owner.id(), id, key, decode(raw, CreatePreparationLink.class)));
    }

    @PostMapping(value = "/preparation-links/{linkId}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreparationLink> status(@PathVariable("id") String id, @PathVariable("linkId") String linkId,
                                                   @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(service.status(owner.id(), id, linkId, key, decode(raw, ChangePreparationLinkStatus.class)));
    }

    @PutMapping(value = "/preparation-links/{linkId}/collection-version", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreparationLink> version(@PathVariable("id") String id, @PathVariable("linkId") String linkId,
                                                    @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(service.version(owner.id(), id, linkId, key, decode(raw, ChangeCollectionVersion.class)));
    }

    @GetMapping("/preparation-links/{linkId}/history")
    public ResponseEntity<LinkHistoryPage> history(@PathVariable("id") String id, @PathVariable("linkId") String linkId,
                                                    @RequestParam MultiValueMap<String, String> query) {
        parameters(query, Set.of("before", "limit"));
        return response(service.history(owner.id(), id, linkId, before(query.getFirst("before")), pageSize(query.getFirst("limit"))));
    }

    @PostMapping(value = "/reschedule-plan-preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReschedulePlanPreview> preview(@PathVariable("id") String id, @RequestBody byte[] raw) {
        return response(service.preview(owner.id(), id, decode(raw, ReschedulePlanRequest.class)));
    }

    @PostMapping(value = "/reschedule-plan", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AppliedReschedulePlan> apply(@PathVariable("id") String id,
                                                       @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(service.apply(owner.id(), id, key, decode(raw, ApplyReschedulePlan.class)));
    }

    private <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
