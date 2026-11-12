package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.CurrentOwner;
import com.wenjie.studio.knowledge.KnowledgeDtos.ItemPage;
import com.wenjie.studio.knowledge.KnowledgeDtos.Kind;
import com.wenjie.studio.knowledge.KnowledgeDtos.Status;
import com.wenjie.studio.knowledge.OrganizationDtos.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge")
public class OrganizationController {
    private final CurrentOwner owner;
    private final OrganizationService organization;
    private final KnowledgeReferenceService references;

    public OrganizationController(CurrentOwner owner, OrganizationService organization, KnowledgeReferenceService references) {
        this.owner = owner;
        this.organization = organization;
        this.references = references;
    }

    @GetMapping("/items/{id}/organization")
    public Organization organization(@PathVariable("id") String id) { return organization.get(owner.id(), id); }

    @PutMapping("/items/{id}/organization")
    public Organization save(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                              @RequestBody OrganizationRequest request) {
        return organization.save(owner.id(), id, key, request);
    }

    @PostMapping("/items/{id}/visit")
    public VisitResult visit(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                             @RequestBody EmptyRequest request) { return organization.visit(owner.id(), id, key, request); }

    @GetMapping("/items/{id}/connections")
    public ConnectionPage connections(@PathVariable("id") String id, @RequestParam("type") ConnectionKind type,
                                       @RequestParam(name = "cursor", required = false) String cursor,
                                       @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return organization.connections(owner.id(), id, type, cursor, limit);
    }

    @PostMapping("/references/rebuild")
    public ReferencesRebuildResult rebuild(@RequestHeader("Idempotency-Key") String key, @RequestBody EmptyRequest request) {
        return references.rebuild(owner.id(), key, request);
    }

    @GetMapping("/library")
    public ItemPage library(@RequestParam(name = "collection", defaultValue = "ALL") Collection collection,
                             @RequestParam(name = "kind", required = false) Kind kind,
                             @RequestParam(name = "status", defaultValue = "ACTIVE") Status status,
                             @RequestParam(name = "q", required = false) String query,
                             @RequestParam(name = "cursor", required = false) String cursor,
                             @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return organization.library(owner.id(), collection, kind, status, query, cursor, limit);
    }
}
