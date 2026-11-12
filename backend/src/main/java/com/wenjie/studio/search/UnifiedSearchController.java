package com.wenjie.studio.search;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
public final class UnifiedSearchController {
    private final CurrentOwner owner;
    private final UnifiedSearchService search;

    public UnifiedSearchController(CurrentOwner owner, UnifiedSearchService search) {
        this.owner = owner;
        this.search = search;
    }

    @GetMapping
    public UnifiedSearchDtos.Page search(@RequestParam String domain, @RequestParam String q,
                                          @RequestParam(defaultValue = "ACTIVE") String status,
                                          @RequestParam(defaultValue = "20") int limit,
                                          @RequestParam(defaultValue = "0") int offset) {
        return search.search(owner.id(), domain, q, status, limit, offset);
    }

    @GetMapping("/sources/{domain}/{id}")
    public UnifiedSearchDtos.Source source(@PathVariable String domain, @PathVariable String id,
                                            @RequestParam String versionId, @RequestParam String q,
                                            @RequestParam(required = false) Integer revision,
                                            @RequestParam(required = false) String feedbackId,
                                            @RequestParam(required = false) String reportHash) {
        return search.source(owner.id(), domain, id, versionId, q, revision, feedbackId, reportHash);
    }
}
