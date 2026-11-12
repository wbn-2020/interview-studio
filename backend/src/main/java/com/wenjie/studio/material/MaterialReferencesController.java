package com.wenjie.studio.material;

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
import static com.wenjie.studio.material.MaterialReferencesDtos.*;
import static com.wenjie.studio.material.MaterialReferencesService.invalid;

@RestController
@RequestMapping(value = "/api/v1/materials/excerpts/{id}/references", produces = MediaType.APPLICATION_JSON_VALUE)
public class MaterialReferencesController {
    private static final Set<String> QUERY_FIELDS = Set.of("kind", "cursor", "limit");
    private final CurrentOwner owner;
    private final MaterialReferencesService references;

    public MaterialReferencesController(CurrentOwner owner, MaterialReferencesService references) {
        this.owner = owner; this.references = references;
    }

    @GetMapping
    public ResponseEntity<Page> list(@PathVariable("id") String id, @RequestParam MultiValueMap<String, String> query) {
        for (var entry : query.entrySet()) {
            if (!QUERY_FIELDS.contains(entry.getKey()) || entry.getValue().size() != 1
                    || entry.getValue().getFirst() == null || entry.getValue().getFirst().isEmpty()) {
                throw invalid("查询参数未知、重复或为空。");
            }
        }
        Kind kind = MaterialReferencesService.kind(query.getFirst("kind"));
        String rawLimit = query.getFirst("limit");
        if (rawLimit != null && !rawLimit.matches("(?:[1-9]|1[0-9]|20)")) throw invalid("每页数量须为 1 至 20。");
        int limit = rawLimit == null ? 20 : Integer.parseInt(rawLimit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(references.list(owner.id(), id, kind, query.getFirst("cursor"), limit));
    }
}
