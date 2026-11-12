package com.wenjie.studio.prepare;

import com.wenjie.studio.common.CurrentOwner;
import com.wenjie.studio.prepare.PreparationDtos.*;
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

@RestController
@RequestMapping("/api/v1/prepare")
public class PreparationController {
    private final PreparationService preparation;
    private final CurrentOwner currentOwner;

    public PreparationController(PreparationService preparation, CurrentOwner currentOwner) {
        this.preparation = preparation;
        this.currentOwner = currentOwner;
    }

    @GetMapping("/items")
    public ItemPage items(@RequestParam(name = "kind", required = false) Kind kind,
                          @RequestParam(name = "status", defaultValue = "ACTIVE") Status status,
                          @RequestParam(name = "q", required = false) String query,
                          @RequestParam(name = "verification", required = false) Verification verification,
                          @RequestParam(name = "favorite", required = false) Boolean favorite,
                          @RequestParam(name = "projectId", required = false) String projectId,
                          @RequestParam(name = "cursor", required = false) String cursor,
                          @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return preparation.list(currentOwner.id(), kind, status, query, verification, favorite, projectId, cursor, limit);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public Item create(@RequestHeader("Idempotency-Key") String key, @RequestBody CreateItem request) {
        return preparation.create(currentOwner.id(), key, request);
    }

    @GetMapping("/items/{id}")
    public Item item(@PathVariable("id") String id) { return preparation.detail(currentOwner.id(), id); }

    @PutMapping("/items/{id}")
    public Item save(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                      @RequestBody SaveItem request) { return preparation.save(currentOwner.id(), id, key, request); }

    @PostMapping("/items/{id}/archive")
    public Item archive(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                         @RequestBody ChangeStatus request) {
        return preparation.status(currentOwner.id(), id, key, request, Status.ARCHIVED);
    }

    @PostMapping("/items/{id}/restore")
    public Item restore(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                         @RequestBody ChangeStatus request) {
        return preparation.status(currentOwner.id(), id, key, request, Status.ACTIVE);
    }

    @PostMapping("/items/{id}/confirm")
    public Item confirm(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                         @RequestBody DecideFact request) {
        return preparation.decide(currentOwner.id(), id, key, request, Verification.CONFIRMED);
    }

    @PostMapping("/items/{id}/withdraw")
    public Item withdraw(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                          @RequestBody DecideFact request) {
        return preparation.decide(currentOwner.id(), id, key, request, Verification.WITHDRAWN);
    }

    @PutMapping("/items/{id}/favorite")
    public Item favorite(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                          @RequestBody SetFavorite request) { return preparation.favorite(currentOwner.id(), id, key, request); }

    @GetMapping("/items/{id}/versions")
    public VersionPage versions(@PathVariable("id") String id,
                                 @RequestParam(name = "before", required = false) Integer before,
                                 @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return preparation.versions(currentOwner.id(), id, before, limit);
    }

    @GetMapping("/items/{id}/versions/{versionId}")
    public Version version(@PathVariable("id") String id, @PathVariable("versionId") String versionId) {
        return preparation.version(currentOwner.id(), id, versionId);
    }

    @GetMapping("/items/{id}/decisions")
    public DecisionPage decisions(@PathVariable("id") String id,
                                   @RequestParam(name = "before", required = false) String before,
                                   @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return preparation.decisions(currentOwner.id(), id, before, limit);
    }

    @GetMapping("/items/{id}/impacts")
    public ImpactPage impacts(@PathVariable("id") String id,
                               @RequestParam(name = "cursor", required = false) String cursor,
                               @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return preparation.impacts(currentOwner.id(), id, cursor, limit);
    }

    @GetMapping("/focus")
    public Focus focus() { return preparation.focus(currentOwner.id()); }

    @PutMapping("/focus")
    public Focus focus(@RequestHeader("Idempotency-Key") String key, @RequestBody SetFocus request) {
        return preparation.focus(currentOwner.id(), key, request);
    }
}
