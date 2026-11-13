package com.wenjie.studio.search;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge/search")
public final class SearchController {
    private final CurrentOwner owner;
    private final SearchService search;

    public SearchController(CurrentOwner owner, SearchService search) { this.owner = owner; this.search = search; }

    @GetMapping
    public SearchDtos.Results search(@RequestParam String q, @RequestParam(required = false) String kind,
                                     @RequestParam(defaultValue = "ACTIVE") String status,
                                     @RequestParam(required = false) String topicId,
                                     @RequestParam(required = false) String tag,
                                     @RequestParam(defaultValue = "ALL") String source,
                                     @RequestParam(defaultValue = "20") int limit,
                                     @RequestParam(defaultValue = "0") int offset) {
        return search.search(owner.id(), q, kind, status, topicId, tag, source, limit, offset);
    }

    @GetMapping("/status")
    public SearchDtos.Status status() { return search.status(owner.id()); }

    @PostMapping("/rebuild")
    public SearchDtos.RebuildResult rebuild(@RequestHeader("Idempotency-Key") String key,
                                           @RequestBody SearchDtos.RebuildRequest request) {
        return search.rebuild(owner.id(), key, request);
    }
}
