package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static com.wenjie.studio.knowledge.KnowledgeGuards.*;

@RestController
@RequestMapping(value = "/api/v1/knowledge/items/{id}/versions/{versionId}/sources",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class KnowledgeSourceController {
    private static final Set<String> SAVE_FIELDS = Set.of("expectedRevision", "sources");
    private static final Set<String> SOURCE_FIELDS = Set.of("title", "url", "author", "acquiredAt", "applicableVersion", "rights", "note");
    private final CurrentOwner owner;
    private final KnowledgeSourceService service;
    private final JsonMapper requests;

    public KnowledgeSourceController(CurrentOwner owner, KnowledgeSourceService service) {
        this.owner = owner; this.service = service;
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(5)
                                .maxStringLength(4000).maxNumberLength(16).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @GetMapping
    public ResponseEntity<KnowledgeSourceDtos.Document> get(@PathVariable("id") String id, @PathVariable("versionId") String versionId,
                                                             @RequestParam MultiValueMap<String, String> query) {
        if (query.entrySet().stream().anyMatch(entry -> !entry.getKey().equals("revision") || entry.getValue().size() != 1)) {
            throw invalid("来源记录只接受单一的历史修订参数。");
        }
        String rawRevision = query.getFirst("revision");
        Long selected = null;
        if (rawRevision != null) {
            if (!rawRevision.matches("[1-9][0-9]{0,15}")) throw invalid("历史来源修订号无效。");
            selected = Long.parseLong(rawRevision);
            revision(selected);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(owner.id(), id, versionId, selected));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<KnowledgeSourceDtos.Document> save(@PathVariable("id") String id, @PathVariable("versionId") String versionId,
                                                             @RequestHeader("Idempotency-Key") String key,
                                                             @RequestParam MultiValueMap<String, String> query,
                                                             @RequestBody byte[] raw) {
        if (!query.isEmpty() || raw == null || raw.length == 0 || raw.length > 1_048_576) throw invalid("来源记录请求大小或查询参数无效。");
        KnowledgeSourceDtos.Save request;
        try {
            JsonNode body = requests.readTree(raw);
            fields(body, SAVE_FIELDS);
            if (!body.get("expectedRevision").isIntegralNumber() || !body.get("expectedRevision").canConvertToLong()
                    || !body.get("sources").isArray() || body.get("sources").size() > 20) throw invalid("来源记录字段类型无效。");
            for (JsonNode source : body.get("sources")) {
                fields(source, SOURCE_FIELDS);
                for (String field : SOURCE_FIELDS) {
                    if (field.equals("acquiredAt") && source.get(field).isNull()) continue;
                    if (!source.get(field).isTextual()) throw invalid("来源字段须为文本，获取时间可以为空值。");
                }
            }
            request = requests.treeToValue(body, KnowledgeSourceDtos.Save.class);
        } catch (RuntimeException exception) { throw invalid("来源记录字段、类型或获取时间无效。"); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.save(owner.id(), id, versionId, key, request));
    }

    private static void fields(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject() || value.size() != fields.size()
                || fields.stream().anyMatch(field -> !value.has(field))) throw invalid("来源记录字段无效。");
    }
}
