package com.wenjie.studio.schedule;

import com.wenjie.studio.common.CurrentOwner;
import com.wenjie.studio.schedule.ScheduleDtos.*;
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
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/schedule")
public class ScheduleController {
    private final ScheduleService schedule;
    private final CurrentOwner currentOwner;

    public ScheduleController(ScheduleService schedule, CurrentOwner currentOwner) {
        this.schedule = schedule;
        this.currentOwner = currentOwner;
    }

    @PostMapping("/time/resolve")
    public TimeResolution resolve(@RequestBody JsonNode request) { return schedule.resolve(currentOwner.id(), request); }

    @GetMapping("/interviews")
    public EventPage list(@RequestParam("from") String from, @RequestParam("to") String to,
                          @RequestParam(name = "targetId", required = false) String targetId,
                          @RequestParam(name = "status", required = false) Status status,
                          @RequestParam(name = "cursor", required = false) String cursor,
                          @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return schedule.list(currentOwner.id(), from, to, targetId, status, cursor, limit);
    }

    @PostMapping("/interviews")
    @ResponseStatus(HttpStatus.CREATED)
    public InterviewEvent create(@RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request) {
        return schedule.create(currentOwner.id(), key, request);
    }

    @GetMapping("/interviews/{id}")
    public InterviewEvent detail(@PathVariable("id") String id) { return schedule.detail(currentOwner.id(), id); }

    @PutMapping("/interviews/{id}")
    public InterviewEvent edit(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                               @RequestBody JsonNode request) { return schedule.edit(currentOwner.id(), id, key, request); }

    @PostMapping("/interviews/{id}/reschedule-preview")
    public ReschedulePreview preview(@PathVariable("id") String id, @RequestBody JsonNode request) {
        return schedule.preview(currentOwner.id(), id, request);
    }

    @PostMapping("/interviews/{id}/reschedule")
    public InterviewEvent reschedule(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                     @RequestBody JsonNode request) { return schedule.reschedule(currentOwner.id(), id, key, request); }

    @PostMapping("/interviews/{id}/cancel")
    public InterviewEvent cancel(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                 @RequestBody JsonNode request) { return schedule.decide(currentOwner.id(), id, key, request, Status.CANCELLED); }

    @PostMapping("/interviews/{id}/complete")
    public InterviewEvent complete(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                   @RequestBody JsonNode request) { return schedule.decide(currentOwner.id(), id, key, request, Status.COMPLETED); }

    @PostMapping("/interviews/{id}/acknowledge")
    public InterviewEvent acknowledge(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                      @RequestBody JsonNode request) { return schedule.acknowledge(currentOwner.id(), id, key, request); }

    @GetMapping("/interviews/{id}/history")
    public HistoryPage history(@PathVariable("id") String id,
                               @RequestParam(name = "before", required = false) Long before,
                               @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return schedule.history(currentOwner.id(), id, before, limit);
    }

    @GetMapping("/interviews/{id}/history/{revision}")
    public InterviewEvent history(@PathVariable("id") String id, @PathVariable("revision") long revision) {
        return schedule.history(currentOwner.id(), id, revision);
    }

    @GetMapping("/reminders")
    public EventPage reminders(@RequestParam(name = "cursor", required = false) String cursor,
                               @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return schedule.reminders(currentOwner.id(), cursor, limit);
    }
}
