package com.wenjie.studio.search;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SearchService {
    private static final int MAX_CANDIDATES = 4096;
    private static final int REVALIDATION_BATCH = 100;
    private final SearchProperties properties;
    private final SearchStore store;
    private final SearchRuntime runtime;
    private final IdempotencyService commands;

    public SearchService(SearchProperties properties, SearchStore store, SearchRuntime runtime, IdempotencyService commands) {
        this.properties = properties;
        this.store = store;
        this.runtime = runtime;
        this.commands = commands;
    }

    public SearchDtos.Status status(String owner) {
        if (!properties.enabled()) return new SearchDtos.Status(SearchDtos.State.DISABLED, null, store.pending(owner), null);
        SearchRows.Projection projection = store.projection(owner);
        long pending = store.pending(owner);
        String error = runtime.error() != null ? runtime.error() : projection == null ? null : projection.lastErrorCode();
        SearchDtos.State state = error != null ? SearchDtos.State.DEGRADED
                : projection == null || projection.buildGeneration() != null || pending > 0
                    || !runtime.verified(projection.activeGeneration()) ? SearchDtos.State.BUILDING
                : SearchDtos.State.READY;
        return new SearchDtos.Status(state, projection == null ? null : projection.activeGeneration(), pending, error);
    }

    public SearchDtos.RebuildResult rebuild(String owner, String key, SearchDtos.RebuildRequest request) {
        return commands.execute(owner, key, "POST /api/v1/knowledge/search/rebuild", request,
                SearchDtos.RebuildResult.class, () -> {
                    if (!properties.enabled()) return new SearchDtos.RebuildResult(false, SearchDtos.State.DISABLED);
                    SearchRows.Projection existing = store.projection(owner);
                    if (existing != null && existing.buildGeneration() != null) {
                        return new SearchDtos.RebuildResult(false, SearchDtos.State.BUILDING);
                    }
                    store.queue(owner);
                    return new SearchDtos.RebuildResult(true, SearchDtos.State.BUILDING);
                });
    }

    public SearchDtos.Results search(String owner, String query, String kind, String status,
                                     String topicId, String tag, String source, int limit, int offset) {
        SearchDtos.Criteria criteria = validate(query, kind, status, topicId, tag, source, limit, offset);
        SearchDtos.Status current = status(owner);
        if (current.state() == SearchDtos.State.DISABLED || current.generation() == null) {
            return new SearchDtos.Results(List.of(), false, current.state(), false);
        }
        SearchRows.Window window = runtime.search(current.generation(), criteria, MAX_CANDIDATES);
        List<SearchDtos.Hit> hits = new ArrayList<>();
        boolean skipped = false;
        int validated = 0;
        // offset 按回验后的可见结果计算；每批继续补齐，过期候选不能提前终结一页。
        for (int start = 0; start < window.candidates().size() && hits.size() <= criteria.limit(); start += REVALIDATION_BATCH) {
            List<SearchRows.Candidate> batch = window.candidates().subList(start,
                    Math.min(window.candidates().size(), start + REVALIDATION_BATCH));
            Map<String, SearchRows.DocumentData> currentDocuments = store.documents(owner,
                            batch.stream().map(SearchRows.Candidate::id).toList()).stream()
                    .collect(Collectors.toMap(data -> data.content().id(), Function.identity()));
            for (SearchRows.Candidate candidate : batch) {
                SearchRows.DocumentData data = currentDocuments.get(candidate.id());
                // 标题、摘要和标签只来自数据库快照，过期索引不直接提供任何展示文本。
                if (data == null || !matches(candidate, data, criteria)) { skipped = true; continue; }
                if (validated++ < criteria.offset()) continue;
                SearchRows.Content content = data.content();
                hits.add(new SearchDtos.Hit(content.id(), content.kind(), content.title(), content.status(),
                        content.versionId(), content.versionNumber(), excerpt(content.body(), criteria.query()),
                        matchedFields(data, criteria.query()), data.topicIds(), data.tags()));
                if (hits.size() > criteria.limit()) break;
            }
        }
        SearchDtos.State state = runtime.error() != null ? SearchDtos.State.DEGRADED
                : skipped ? SearchDtos.State.BUILDING : current.state();
        // 下一页提示也只依据已回验的额外命中，不能用过期索引总数暗示隐藏内容。
        boolean extraHit = hits.size() > criteria.limit();
        boolean pageLimited = extraHit && criteria.offset() + criteria.limit() > 1000;
        boolean hasMore = extraHit && !pageLimited;
        boolean scanLimited = pageLimited || !hasMore && window.hasMore();
        return new SearchDtos.Results(List.copyOf(hits.subList(0, Math.min(hits.size(), criteria.limit()))),
                hasMore, state, scanLimited);
    }

    private static boolean matches(SearchRows.Candidate candidate, SearchRows.DocumentData data, SearchDtos.Criteria criteria) {
        SearchRows.Content content = data.content();
        return candidate.versionId().equals(content.versionId())
                && candidate.metadataRevision() == content.metadataRevision()
                && candidate.organizationRevision() == content.organizationRevision()
                && criteria.status().equals(content.status()) && criteria.source().equals(content.source())
                && (criteria.kind() == null || criteria.kind().equals(content.kind()))
                && (criteria.topicId() == null || data.topicIds().contains(criteria.topicId()))
                && (criteria.tag() == null || data.tags().stream().anyMatch(tag -> tag.equalsIgnoreCase(criteria.tag())));
    }

    private static List<String> matchedFields(SearchRows.DocumentData data, String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        List<String> fields = new ArrayList<>();
        if (data.content().title().toLowerCase(Locale.ROOT).contains(normalized)) fields.add("title");
        if (data.content().body().toLowerCase(Locale.ROOT).contains(normalized)) fields.add("body");
        if (data.aliases().stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(normalized))) fields.add("aliases");
        if (data.tags().stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(normalized))) fields.add("tags");
        return fields.isEmpty() ? List.of("analyzedContent") : List.copyOf(fields);
    }

    private static String excerpt(String body, String query) {
        // 大小写转换可能扩展字符，定位必须保持原文坐标，不能复用转换后文本的下标。
        int found = -1;
        for (int index = 0; index <= body.length() - query.length(); index++) {
            if (body.regionMatches(true, index, query, 0, query.length())) {
                found = index;
                break;
            }
        }
        int start = found < 0 ? 0 : Math.max(0, found - 60);
        int end = Math.min(body.length(), start + 300);
        if (start > 0 && Character.isLowSurrogate(body.charAt(start))) start--;
        if (end < body.length() && end > 0 && Character.isHighSurrogate(body.charAt(end - 1))) end--;
        return (start > 0 ? "…" : "") + body.substring(start, end) + (end < body.length() ? "…" : "");
    }

    private static SearchDtos.Criteria validate(String query, String kind, String status, String topicId,
                                                String tag, String source, int limit, int offset) {
        if (query == null || query.strip().isEmpty() || query.strip().length() > 100 || limit < 1 || limit > 50
                || offset < 0 || offset > 1000) throw invalid();
        if (kind != null && !List.of("NOTE", "CONCEPT").contains(kind)) throw invalid();
        if (!List.of("ACTIVE", "ARCHIVED").contains(status) || !"USER".equals(source)) throw invalid();
        if (topicId != null) {
            try { if (!UUID.fromString(topicId).toString().equals(topicId)) throw invalid(); }
            catch (IllegalArgumentException exception) { throw invalid(); }
        }
        if (tag != null && (tag.strip().isEmpty() || tag.strip().length() > 40)) throw invalid();
        return new SearchDtos.Criteria(query.strip(), kind, status, topicId, tag == null ? null : tag.strip(), source, limit, offset);
    }

    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "请检查检索词、筛选条件和分页范围。"); }
}
