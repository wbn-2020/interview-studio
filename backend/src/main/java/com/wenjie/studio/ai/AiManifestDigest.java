package com.wenjie.studio.ai;

import org.springframework.stereotype.Component;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class AiManifestDigest {
    private final JsonMapper canonical;

    public AiManifestDigest(JsonMapper json) {
        canonical = json.rebuild().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    }

    public String hash(AiDtos.Manifest manifest) {
        try {
            Object payload = manifest;
            if (manifest != null && (manifest.knowledgeMaterialChecks() == null || manifest.materialChecks() == null || manifest.sourceQa() == null || manifest.relationAiFeedback() == null || manifest.relationEvidence() == null || manifest.factDraft() == null
                    || manifest.cardDraftType() == null || manifest.reviewDraft() == null)) {
                // 各次新增且为空的尾字段只从摘要投影省略，保留历史摘要与其他空值，不改写原清单。
                ObjectNode previous = (ObjectNode) canonical.valueToTree(manifest);
                if (manifest.knowledgeMaterialChecks() == null) previous.remove("knowledgeMaterialChecks");
                if (manifest.materialChecks() == null) previous.remove("materialChecks");
                if (manifest.sourceQa() == null) previous.remove("sourceQa");
                if (manifest.relationAiFeedback() == null) previous.remove("relationAiFeedback");
                if (manifest.relationEvidence() == null) previous.remove("relationEvidence");
                if (manifest.factDraft() == null) previous.remove("factDraft");
                if (manifest.cardDraftType() == null) previous.remove("cardDraftType");
                if (manifest.reviewDraft() == null) previous.remove("reviewDraft");
                payload = previous;
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.writeValueAsBytes(payload)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行时不支持资料清单摘要", exception);
        }
    }
}
