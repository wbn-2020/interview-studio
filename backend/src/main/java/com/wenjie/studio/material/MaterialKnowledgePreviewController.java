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

@RestController
@RequestMapping(value = "/api/v1/materials/excerpts/{id}/knowledge-adoptions", produces = MediaType.APPLICATION_JSON_VALUE)
public class MaterialKnowledgePreviewController {
    private final CurrentOwner owner;
    private final MaterialKnowledgePreviewService service;

    public MaterialKnowledgePreviewController(CurrentOwner owner, MaterialKnowledgePreviewService service) {
        this.owner = owner; this.service = service;
    }

    @GetMapping
    public ResponseEntity<MaterialKnowledgePreviewDtos.Preview> preview(@PathVariable("id") String id,
                                                                      @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw MaterialGuards.invalid("知识采用链预览不接受查询参数。");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preview(owner.id(), id));
    }
}
