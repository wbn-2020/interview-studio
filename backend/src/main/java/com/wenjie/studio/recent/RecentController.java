package com.wenjie.studio.recent;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
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
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static com.wenjie.studio.recent.RecentDtos.*;
import static com.wenjie.studio.recent.RecentService.invalid;

@RestController
@RequestMapping(value = "/api/v1/recent", produces = MediaType.APPLICATION_JSON_VALUE)
public class RecentController {
    private static final Set<String> QUERY_FIELDS = Set.of("kind", "cursor", "limit");
    private static final JsonMapper STRICT = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
                            .maxStringLength(128).maxNumberLength(16).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final CurrentOwner owner;
    private final RecentService recent;

    public RecentController(CurrentOwner owner, RecentService recent) {
        this.owner = owner;
        this.recent = recent;
    }

    @GetMapping
    public ResponseEntity<Page> list(@RequestParam MultiValueMap<String, String> query) {
        for (var entry : query.entrySet()) {
            if (!QUERY_FIELDS.contains(entry.getKey()) || entry.getValue().size() != 1) {
                throw invalid("查询参数未知或重复。");
            }
        }
        Kind kind = query.containsKey("kind") ? RecentService.kind(query.getFirst("kind")) : null;
        String rawLimit = query.getFirst("limit");
        if (rawLimit != null && !rawLimit.matches("(?:[1-9]|1[0-9]|20)")) throw invalid("每页数量须为 1 至 20。");
        int limit = rawLimit == null ? 20 : Integer.parseInt(rawLimit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(recent.list(owner.id(), kind, query.getFirst("cursor"), limit));
    }

    @PostMapping(value = "/visits", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Visit> visit(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(recent.visit(owner.id(), key, decode(raw)));
    }

    private static VisitRequest decode(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > 1024) throw invalid("访问请求须为不超过 1 KiB 的 JSON 对象。");
        try {
            // 在反序列化前核验原始形状，避免重复字段覆盖或标量强转改变幂等请求身份。
            var node = STRICT.readTree(raw);
            if (node == null || !node.isObject() || node.size() != 2 || node.get("kind") == null
                    || !node.get("kind").isTextual() || node.get("id") == null || !node.get("id").isTextual()) {
                throw invalid("访问请求只接受 kind 与 id 两个字符串字段。");
            }
            return new VisitRequest(RecentService.kind(node.get("kind").stringValue()), node.get("id").stringValue());
        } catch (ApiException exception) { throw exception; }
        catch (RuntimeException exception) { throw invalid("访问请求格式无效，或包含重复字段。"); }
    }
}
