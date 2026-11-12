package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.CurrentOwner;
import com.wenjie.studio.knowledge.KnowledgeDtos.*;
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
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
    private final KnowledgeService knowledge;
    private final CurrentOwner currentOwner;

    public KnowledgeController(KnowledgeService knowledge, CurrentOwner currentOwner) {
        this.knowledge = knowledge;
        this.currentOwner = currentOwner;
    }

    @GetMapping("/items")
    public ItemPage items(@RequestParam(name = "kind", required = false) String kind,
                          @RequestParam(name = "status", defaultValue = "ACTIVE") String status,
                          @RequestParam(name = "q", required = false) String query,
                          @RequestParam(name = "cursor", required = false) String cursor,
                          @RequestParam(name = "limit", defaultValue = "40") int limit) {
        return knowledge.list(currentOwner.id(), kind, status, query, cursor, limit);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemDetail create(@RequestHeader("Idempotency-Key") String key, @RequestBody CreateItem request) {
        return knowledge.create(currentOwner.id(), key, request);
    }

    @GetMapping("/items/{id}")
    public ItemDetail detail(@PathVariable("id") String id) {
        return knowledge.detail(currentOwner.id(), id);
    }

    @PutMapping("/items/{id}/draft")
    public ItemDetail saveDraft(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                @RequestBody SaveDraft request) {
        return knowledge.saveDraft(currentOwner.id(), id, key, request);
    }

    @PostMapping("/items/{id}/publish")
    public ItemDetail publish(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                              @RequestBody Publish request) {
        return knowledge.publish(currentOwner.id(), id, key, request);
    }

    @PutMapping("/items/{id}/topics")
    public ItemDetail setTopics(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                @RequestBody SetTopics request) {
        return knowledge.setTopics(currentOwner.id(), id, key, request);
    }

    @PostMapping("/items/{id}/archive")
    public ItemDetail archive(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                              @RequestBody ChangeStatus request) {
        return knowledge.changeStatus(currentOwner.id(), id, key, request, Status.ARCHIVED);
    }

    @PostMapping("/items/{id}/restore")
    public ItemDetail restore(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                              @RequestBody ChangeStatus request) {
        return knowledge.changeStatus(currentOwner.id(), id, key, request, Status.ACTIVE);
    }

    @GetMapping("/items/{id}/versions")
    public VersionPage versions(@PathVariable("id") String id,
                                @RequestParam(name = "before", required = false) Integer before,
                                @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return knowledge.versions(currentOwner.id(), id, before, limit);
    }

    @GetMapping("/items/{id}/versions/{versionId}")
    public Version version(@PathVariable("id") String id, @PathVariable("versionId") String versionId) {
        return knowledge.version(currentOwner.id(), id, versionId);
    }

    @GetMapping("/topics")
    public TopicList topics() {
        return knowledge.topics(currentOwner.id());
    }

    @PostMapping("/topics")
    @ResponseStatus(HttpStatus.CREATED)
    public Topic createTopic(@RequestHeader("Idempotency-Key") String key, @RequestBody CreateTopic request) {
        return knowledge.createTopic(currentOwner.id(), key, request);
    }

    @PostMapping("/relations")
    @ResponseStatus(HttpStatus.CREATED)
    public Relation createRelation(@RequestHeader("Idempotency-Key") String key,
                                    @RequestBody CreateRelation request) {
        return knowledge.createRelation(currentOwner.id(), key, request);
    }

    @PostMapping("/relations/{id}/remove")
    public Removed removeRelation(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key,
                                   @RequestBody RemoveRelation request) {
        return knowledge.removeRelation(currentOwner.id(), id, key, request);
    }

    @GetMapping("/graph")
    public Graph graph(@RequestParam(name = "limit", defaultValue = "200") int limit) {
        return knowledge.graph(currentOwner.id(), limit);
    }

    @PutMapping("/graph/layout")
    public LayoutSaved saveLayout(@RequestHeader("Idempotency-Key") String key, @RequestBody SaveLayout request) {
        return knowledge.saveLayout(currentOwner.id(), key, request);
    }
}
