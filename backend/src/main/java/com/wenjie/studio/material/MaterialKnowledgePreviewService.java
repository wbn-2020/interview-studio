package com.wenjie.studio.material;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.wenjie.studio.material.MaterialKnowledgePreviewDtos.Preview;

@Service
public class MaterialKnowledgePreviewService {
    private final MaterialLifecycleService lifecycle;
    private final MaterialKnowledgeAdoptions adoptions;

    public MaterialKnowledgePreviewService(MaterialLifecycleService lifecycle, MaterialKnowledgeAdoptions adoptions) {
        this.lifecycle = lifecycle; this.adoptions = adoptions;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public Preview preview(String owner, String excerptId) {
        return adoptions.collect(owner, excerptId, lifecycle.state(owner, excerptId));
    }
}
