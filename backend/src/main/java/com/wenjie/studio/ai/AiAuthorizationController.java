package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static com.wenjie.studio.ai.AiAuthorizationDtos.*;

@RestController
@RequestMapping("/api/v1/ai")
public class AiAuthorizationController {
    private static final Set<String> CREATE_FIELDS = Set.of("taskId", "expectedManifestHash", "validDays", "confirmed");
    private static final Set<String> REVOKE_FIELDS = Set.of("expectedRevision", "confirmed");
    private final AiAuthorizations authorizations;
    private final AiService service;
    private final CurrentOwner owner;
    private final JsonMapper requests;
    private final JsonMapper matchRequests;

    public AiAuthorizationController(AiAuthorizations authorizations, AiService service, CurrentOwner owner) {
        this.authorizations = authorizations;
        this.service = service;
        this.owner = owner;
        // 原始字节拒绝重复键和类型强转，避免静默改写用户确认的授权范围与有效期。
        requests = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                                .maxStringLength(131072).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
        matchRequests = requests.rebuild().disable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build();
    }

    @GetMapping("/authorizations")
    public Page page(@RequestParam(name = "cursor", required = false) String cursor,
                     @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return authorizations.page(owner.id(), cursor, limit);
    }

    @PostMapping(value = "/authorizations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Entry create(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        Create request;
        try {
            JsonNode root = tree(raw);
            fields(root, CREATE_FIELDS);
            if (!root.get("taskId").isTextual() || !root.get("expectedManifestHash").isTextual()
                    || !root.get("validDays").isIntegralNumber() || !root.get("validDays").canConvertToInt()
                    || !root.get("confirmed").isBoolean()) throw invalid();
            request = requests.treeToValue(root, Create.class);
        } catch (RuntimeException exception) { throw invalid(); }
        return service.createAuthorization(owner.id(), key, request);
    }

    @PostMapping(value = "/authorizations/matches", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Matches matches(@RequestBody byte[] raw) {
        MatchRequest request;
        try {
            JsonNode root = tree(raw);
            fields(root, Set.of("manifest"));
            JsonNode manifest = root.get("manifest");
            if (!manifest.isObject()) throw invalid();
            // 旧清单的可选尾字段可缺省；七个共同必填字段仍保留原始类型和非空边界。
            for (String name : Set.of("purpose", "instruction", "sources", "configuration", "inputTokenAllowance", "reservedMicros", "promptVersion")) {
                if (!manifest.has(name) || manifest.get(name).isNull()) throw invalid();
            }
            if (!manifest.get("purpose").isTextual() || !manifest.get("instruction").isTextual()
                    || !manifest.get("sources").isArray() || !manifest.get("configuration").isObject()
                    || !manifest.get("inputTokenAllowance").isIntegralNumber() || !manifest.get("inputTokenAllowance").canConvertToInt()
                    || !manifest.get("reservedMicros").isIntegralNumber() || !manifest.get("reservedMicros").canConvertToLong()
                    || !manifest.get("promptVersion").isTextual()) throw invalid();
            request = matchRequests.treeToValue(root, MatchRequest.class);
        } catch (RuntimeException exception) { throw invalid(); }
        return authorizations.matches(owner.id(), request);
    }

    @PostMapping(value = "/authorizations/{id}/revoke", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Revocation revoke(@PathVariable("id") String id, @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        Revoke request;
        try {
            JsonNode root = tree(raw);
            fields(root, REVOKE_FIELDS);
            if (!root.get("expectedRevision").isIntegralNumber() || !root.get("expectedRevision").canConvertToLong()
                    || !root.get("confirmed").isBoolean()) throw invalid();
            request = requests.treeToValue(root, Revoke.class);
        } catch (RuntimeException exception) { throw invalid(); }
        return service.revokeAuthorization(owner.id(), id, key, request);
    }

    @GetMapping("/tasks/{id}/authorization-uses")
    public UsagePage uses(@PathVariable("id") String id) { return service.authorizationUses(owner.id(), id); }

    private JsonNode tree(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > 262144) throw invalid();
        return requests.readTree(raw);
    }
    private static void fields(JsonNode value, Set<String> names) {
        if (value == null || !value.isObject() || value.size() != names.size() || names.stream().anyMatch(name -> !value.has(name))) throw invalid();
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "持续授权请求的字段或类型无效。"); }
}
