package com.wenjie.studio.lifecycle;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static com.wenjie.studio.lifecycle.KnowledgeReferencesDtos.*;
import static com.wenjie.studio.lifecycle.KnowledgeReferencesService.invalid;

@RestController
@RequestMapping(value = "/api/v1/knowledge/items/{id}/references", produces = MediaType.APPLICATION_JSON_VALUE)
public class KnowledgeReferencesController {
    private static final Set<String> QUERY_FIELDS = Set.of("versionId", "kind", "cursor", "limit");
    private final CurrentOwner owner;
    private final KnowledgeReferencesService references;

    public KnowledgeReferencesController(CurrentOwner owner, KnowledgeReferencesService references) {
        this.owner = owner;
        this.references = references;
    }

    @GetMapping
    public ResponseEntity<Page> list(@PathVariable("id") String id,
                                     @RequestParam MultiValueMap<String, String> query) {
        for (var entry : query.entrySet()) {
            if (!QUERY_FIELDS.contains(entry.getKey()) || entry.getValue().size() != 1
                    || entry.getValue().getFirst() == null || entry.getValue().getFirst().isEmpty()) {
                throw invalid("查询参数未知、重复或为空。");
            }
        }
        String versionId = query.getFirst("versionId");
        if (versionId == null) throw invalid("必须指定确切知识发布版本。");
        Kind kind = KnowledgeReferencesService.kind(query.getFirst("kind"));
        String rawLimit = query.getFirst("limit");
        if (rawLimit != null && !rawLimit.matches("(?:[1-9]|1[0-9]|20)")) {
            throw invalid("每页数量须为 1 至 20。");
        }
        int limit = rawLimit == null ? 20 : Integer.parseInt(rawLimit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(references.list(owner.id(), id, versionId, kind, query.getFirst("cursor"), limit));
    }
}
