package com.wenjie.studio.today;

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

import static com.wenjie.studio.today.TodayDtos.*;
import static com.wenjie.studio.today.TodayPlanDtos.*;

@RestController
@RequestMapping(value = "/api/v1/today", produces = MediaType.APPLICATION_JSON_VALUE)
public class TodayPlanController {
    private final TodayPlanService service;
    private final CurrentOwner owner;

    public TodayPlanController(TodayPlanService service, CurrentOwner owner) { this.service = service; this.owner = owner; }

    @GetMapping("/placements/by-source")
    public ResponseEntity<PlacementLookup> lookup(@RequestParam MultiValueMap<String, String> query) {
        TodayRules.parameters(query, Set.of("kind", "sourceId"));
        return response(service.lookup(owner.id(), new SourceRef(TodayRules.enumValue(TodayRules.required(query, "kind"), SourceKind.class),
                TodayRules.required(query, "sourceId"))));
    }

    @GetMapping("/placements")
    public ResponseEntity<PlacementPage> directory(@RequestParam MultiValueMap<String, String> query) {
        TodayRules.parameters(query, Set.of("zoneId", "kind", "visibility", "cursor", "limit"));
        return response(service.directory(owner.id(), new PlacementDirectoryQuery(TodayRules.required(query, "zoneId"),
                query.containsKey("kind") ? TodayRules.enumValue(query.getFirst("kind"), SourceKind.class) : null,
                query.containsKey("visibility") ? TodayRules.enumValue(query.getFirst("visibility"), Visibility.class) : null,
                query.getFirst("cursor"), TodayRules.limit(query.getFirst("limit")))));
    }

    @PostMapping(value = "/placements", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Placement> create(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return created(service.create(owner.id(), key, TodayPlanRules.decode(raw, CreatePlacement.class)));
    }

    @PutMapping(value = "/placements/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Placement> save(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(service.save(owner.id(), id, key, TodayPlanRules.decode(raw, SavePlacement.class)));
    }

    @GetMapping("/placements/{id}/history")
    public ResponseEntity<PlacementHistoryPage> history(@PathVariable("id") String id, @RequestParam MultiValueMap<String, String> query) {
        TodayRules.parameters(query, Set.of("before", "limit"));
        return response(service.history(owner.id(), id, before(query), TodayRules.limit(query.getFirst("limit"))));
    }

    @PostMapping(value = "/order", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderedPlacements> order(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(service.order(owner.id(), key, TodayPlanRules.decode(raw, OrderPlacements.class)));
    }

    @GetMapping("/budgets")
    public ResponseEntity<BudgetLookup> budget(@RequestParam MultiValueMap<String, String> query) {
        TodayRules.parameters(query, Set.of("localDate", "zoneId"));
        return response(service.budget(owner.id(), TodayPlanRules.date(TodayRules.required(query, "localDate")), TodayRules.required(query, "zoneId")));
    }

    @PostMapping(value = "/budgets", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DayBudget> createBudget(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return created(service.createBudget(owner.id(), key, TodayPlanRules.decode(raw, CreateBudget.class)));
    }

    @PutMapping(value = "/budgets/{localDate}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DayBudget> saveBudget(@PathVariable("localDate") String localDate,
                                               @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(service.saveBudget(owner.id(), TodayPlanRules.date(localDate), key, TodayPlanRules.decode(raw, SaveBudget.class)));
    }

    @GetMapping("/budgets/{localDate}/history")
    public ResponseEntity<BudgetHistoryPage> budgetHistory(@PathVariable("localDate") String localDate,
                                                           @RequestParam MultiValueMap<String, String> query) {
        TodayRules.parameters(query, Set.of("zoneId", "before", "limit"));
        return response(service.budgetHistory(owner.id(), TodayPlanRules.date(localDate), TodayRules.required(query, "zoneId"),
                before(query), TodayRules.limit(query.getFirst("limit"))));
    }

    @PostMapping(value = "/suggestions/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuggestionPreview> preview(@RequestBody byte[] raw) {
        return response(service.preview(owner.id(), TodayPlanRules.decode(raw, SuggestionRequest.class)));
    }

    @PostMapping(value = "/suggestions/apply", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AppliedSuggestion> apply(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return response(service.apply(owner.id(), key, TodayPlanRules.decode(raw, ApplySuggestion.class)));
    }

    private Long before(MultiValueMap<String, String> query) {
        String value = query.getFirst("before"); if (value == null) return null;
        if (!value.matches("0|[1-9][0-9]{0,15}")) throw TodayPlanRules.invalid("历史游标须为非负整数。");
        try { long result = Long.parseLong(value); TodayPlanRules.revision(result); return result; }
        catch (NumberFormatException exception) { throw TodayPlanRules.invalid("历史游标超出范围。"); }
    }
    private <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    private <T> ResponseEntity<T> created(T value) { return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(value); }
}
