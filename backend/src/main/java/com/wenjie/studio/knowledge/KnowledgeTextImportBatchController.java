package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

import static com.wenjie.studio.knowledge.KnowledgeGuards.invalid;
import static com.wenjie.studio.knowledge.KnowledgeTextImportBatchDtos.*;

@RestController
@RequestMapping(value = "/api/v1/knowledge/text-import-batches", produces = MediaType.APPLICATION_JSON_VALUE)
public class KnowledgeTextImportBatchController {
    private static final Set<String> FILE_FIELDS = Set.of("fileName", "sourceSha256", "sourceBytes", "sourceCharacters");
    private static final Set<String> DIRECTORY_FILE_FIELDS = Set.of("fileName", "sourceSha256", "sourceBytes",
            "sourceCharacters", "sourcePath");
    private static final Set<String> IMPORT_FIELDS = Set.of("revision", "source", "title", "body", "topicIds", "createCopy");
    private final CurrentOwner owner;
    private final KnowledgeTextImportBatchService service;
    private final JsonMapper requests;

    public KnowledgeTextImportBatchController(CurrentOwner owner, KnowledgeTextImportBatchService service) {
        this.owner = owner; this.service = service;
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
                                .maxStringLength(100000).maxNumberLength(16).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Detail> create(@RequestHeader("Idempotency-Key") String key,
                                         @RequestParam MultiValueMap<String, String> query, @RequestBody byte[] raw) {
        JsonNode body = body(raw, query, Set.of("files"));
        if (!body.get("files").isArray() || body.get("files").isEmpty() || body.get("files").size() > 20) {
            throw invalid("批次须包含1至20个文件元数据项。");
        }
        for (JsonNode file : body.get("files")) {
            fields(file, FILE_FIELDS);
            if (!file.get("fileName").isTextual() || !file.get("sourceSha256").isTextual()
                    || !file.get("sourceBytes").isIntegralNumber() || !file.get("sourceBytes").canConvertToInt()
                    || !file.get("sourceCharacters").isIntegralNumber() || !file.get("sourceCharacters").canConvertToInt()) {
                throw invalid("文件元数据字段类型无效。");
            }
        }
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(service.create(owner.id(), key, convert(body, CreateBatch.class)));
    }

    @PostMapping(value = "/directories", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DirectoryDetail> createDirectory(@RequestHeader("Idempotency-Key") String key,
                                                           @RequestParam MultiValueMap<String, String> query,
                                                           @RequestBody byte[] raw) {
        JsonNode body = body(raw, query, Set.of("files"));
        if (!body.get("files").isArray() || body.get("files").isEmpty() || body.get("files").size() > 20) {
            throw invalid("批次须包含1至20个文件元数据项。");
        }
        for (JsonNode file : body.get("files")) {
            fields(file, DIRECTORY_FILE_FIELDS);
            if (!file.get("fileName").isTextual() || !file.get("sourceSha256").isTextual()
                    || !file.get("sourcePath").isTextual()
                    || !file.get("sourceBytes").isIntegralNumber() || !file.get("sourceBytes").canConvertToInt()
                    || !file.get("sourceCharacters").isIntegralNumber() || !file.get("sourceCharacters").canConvertToInt()) {
                throw invalid("目录文件元数据字段类型无效。");
            }
        }
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(service.createDirectory(owner.id(), key, convert(body, CreateDirectoryBatch.class)));
    }

    @GetMapping
    public ResponseEntity<Page> list(@RequestParam MultiValueMap<String, String> query) {
        if (query.entrySet().stream().anyMatch(entry -> !Set.of("cursor", "limit").contains(entry.getKey())
                || entry.getValue().size() != 1)) throw invalid("批次查询参数无效或重复。");
        String limit = query.getFirst("limit");
        if (limit != null && !limit.matches("(?:[1-9]|[1-4][0-9]|50)")) throw invalid("每页数量须为1至50。");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(owner.id(), query.getFirst("cursor"), limit == null ? 20 : Integer.parseInt(limit)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Detail> detail(@PathVariable String id, @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw invalid("读取批次详情不接受查询参数。");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(owner.id(), id));
    }

    @GetMapping("/{id}/directory")
    public ResponseEntity<DirectoryDetail> directoryDetail(@PathVariable String id,
                                                           @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw invalid("读取批次路径详情不接受查询参数。");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.directoryDetail(owner.id(), id));
    }

    @PostMapping(value = "/{id}/entries/{entryId}/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Detail> importEntry(@PathVariable String id, @PathVariable String entryId,
                                              @RequestHeader("Idempotency-Key") String key,
                                              @RequestParam MultiValueMap<String, String> query, @RequestBody byte[] raw) {
        JsonNode body = body(raw, query, IMPORT_FIELDS);
        if (!body.get("revision").isIntegralNumber() || !body.get("revision").canConvertToLong()
                || !body.get("source").isTextual() || !body.get("title").isTextual() || !body.get("body").isTextual()
                || !body.get("createCopy").isBoolean() || !body.get("topicIds").isArray()
                || body.get("topicIds").size() > 20) throw invalid("单项导入字段类型无效。");
        for (JsonNode topic : body.get("topicIds")) if (!topic.isTextual()) throw invalid("主题标识须为字符串。");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.importEntry(owner.id(), key, id, entryId, convert(body, ImportEntry.class)));
    }

    @PostMapping(value = "/{id}/entries/{entryId}/skip", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Detail> skipEntry(@PathVariable String id, @PathVariable String entryId,
                                            @RequestHeader("Idempotency-Key") String key,
                                            @RequestParam MultiValueMap<String, String> query, @RequestBody byte[] raw) {
        JsonNode body = body(raw, query, Set.of("revision"));
        if (!body.get("revision").isIntegralNumber() || !body.get("revision").canConvertToLong()) {
            throw invalid("批次修订号须为整数。");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.skipEntry(owner.id(), key, id, entryId, convert(body, SkipEntry.class)));
    }

    @PostMapping(value = "/{id}/entries/{entryId}/publish-links", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<KnowledgeDtos.ItemDetail> publishLinks(@PathVariable String id, @PathVariable String entryId,
                                                                 @RequestHeader("Idempotency-Key") String key,
                                                                 @RequestParam MultiValueMap<String, String> query,
                                                                 @RequestBody byte[] raw) {
        JsonNode body = body(raw, query, Set.of("expectedVersionId", "expectedDraftRevision", "body"));
        if (!body.get("expectedVersionId").isTextual() || !body.get("expectedDraftRevision").isIntegralNumber()
                || !body.get("expectedDraftRevision").canConvertToLong() || !body.get("body").isTextual()) {
            throw invalid("导入后发布的版本、草稿修订号或正文字段类型无效。");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.publishLinks(owner.id(), key, id, entryId, convert(body, PublishLinks.class)));
    }

    private JsonNode body(byte[] raw, MultiValueMap<String, String> query, Set<String> expected) {
        if (!query.isEmpty() || raw == null || raw.length == 0 || raw.length > 1_048_576) {
            throw invalid("批次请求大小或查询参数无效。");
        }
        try {
            JsonNode body = requests.readTree(raw);
            fields(body, expected);
            return body;
        } catch (RuntimeException exception) { throw invalid("批次请求字段、结构或类型无效。"); }
    }

    private static void fields(JsonNode body, Set<String> expected) {
        if (body == null || !body.isObject() || body.size() != expected.size()
                || expected.stream().anyMatch(field -> !body.has(field))) throw invalid("批次请求字段无效。");
    }

    private <T> T convert(JsonNode body, Class<T> type) {
        try { return requests.treeToValue(body, type); }
        catch (RuntimeException exception) { throw invalid("批次请求字段类型无效。"); }
    }
}
