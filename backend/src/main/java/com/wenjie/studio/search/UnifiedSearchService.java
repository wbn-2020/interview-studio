package com.wenjie.studio.search;

import com.wenjie.studio.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.wenjie.studio.search.UnifiedSearchDtos.Domain;
import static com.wenjie.studio.search.UnifiedSearchDtos.Hit;
import static com.wenjie.studio.search.UnifiedSearchDtos.MatchMode;
import static com.wenjie.studio.search.UnifiedSearchDtos.Page;
import static com.wenjie.studio.search.UnifiedSearchDtos.Source;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
public class UnifiedSearchService {
    private static final int CANDIDATE_BATCH = 100;
    private static final int MAX_CANDIDATES = 4096;
    private static final int MAX_OFFSET = 1000;
    private final SearchService knowledge;
    private final UnifiedSearchSources sources;
    private final UnifiedSearchAiSources ai;

    public UnifiedSearchService(SearchService knowledge, UnifiedSearchSources sources, UnifiedSearchAiSources ai) {
        this.knowledge = knowledge;
        this.sources = sources;
        this.ai = ai;
    }

    public Page search(String owner, String domainName, String query, String status, int limit, int offset) {
        uuid(owner);
        Domain domain = domain(domainName);
        String normalized = query(query);
        if (!("ACTIVE".equals(status) || "ARCHIVED".equals(status)) || limit < 1 || limit > 50
                || offset < 0 || offset > MAX_OFFSET) throw invalid();
        sources.requireEnabled(domain);
        if (domain == Domain.KNOWLEDGE) return knowledge(owner, normalized, status, limit, offset);
        if ("ARCHIVED".equals(status) && (domain == Domain.MATERIAL_EXCERPT
                || domain == Domain.TRAINING_ANSWER || domain == Domain.REVIEW_FEEDBACK || domain == Domain.AI_FEEDBACK)) {
            return new Page(domain, normalized, status, MatchMode.LITERAL, false, null,
                    List.of(), limit, offset, false, false);
        }
        if (domain == Domain.AI_FEEDBACK) return ai.search(owner, normalized, limit, offset);
        return literal(owner, domain, normalized, status, limit, offset);
    }

    public Source source(String owner, String domainName, String id, String versionId, String query) {
        return source(owner, domainName, id, versionId, query, null);
    }

    public Source source(String owner, String domainName, String id, String versionId, String query, Integer revision) {
        return source(owner, domainName, id, versionId, query, revision, null, null);
    }

    public Source source(String owner, String domainName, String id, String versionId, String query, Integer revision,
                         String feedbackId, String reportHash) {
        uuid(owner);
        Domain domain = domain(domainName);
        uuid(id);
        uuid(versionId);
        if (domain == Domain.REVIEW_FEEDBACK) {
            if (revision == null || revision < 1 || revision > 100 || !id.equals(versionId)) throw invalid();
        } else if (revision != null) throw invalid();
        if (domain == Domain.AI_FEEDBACK) {
            if (feedbackId == null || !feedbackId.matches("f(?:[1-9]|1[0-2])")
                    || reportHash == null || !reportHash.matches("[0-9a-f]{64}")) throw invalid();
        } else if (feedbackId != null || reportHash != null) throw invalid();
        String normalized = query(query);
        sources.requireEnabled(domain);
        Source source = sources.read(owner, domain, id, versionId, revision, feedbackId, reportHash);
        return new Source(located(source, normalized), source.fields());
    }

    private Page knowledge(String owner, String query, String status, int limit, int offset) {
        SearchDtos.Results result = knowledge.search(owner, query, null, status, null, null, "USER", limit, offset);
        var hits = new ArrayList<Hit>();
        boolean skipped = false;
        // 索引回验和确切正文读取处于同一数据库快照；索引中的展示文本不参与定位。
        for (SearchDtos.Hit hit : result.items()) {
            Source source;
            try {
                source = sources.read(owner, Domain.KNOWLEDGE, hit.id(), hit.versionId());
            } catch (ApiException exception) {
                if (exception.status() != 404) throw exception;
                skipped = true;
                continue;
            }
            if (!source.item().currentVersion() || !status.equals(source.item().status())) {
                skipped = true;
                continue;
            }
            hits.add(located(source, query));
        }
        SearchDtos.State state = skipped && result.syncStatus() == SearchDtos.State.READY
                ? SearchDtos.State.BUILDING : result.syncStatus();
        return new Page(Domain.KNOWLEDGE, query, status, MatchMode.ANALYZED, true, state,
                List.copyOf(hits), limit, offset, !skipped && result.hasMore(), skipped || result.scanLimited());
    }

    private Page literal(String owner, Domain domain, String query, String status, int limit, int offset) {
        var hits = new ArrayList<Hit>();
        int scanned = 0;
        int matched = 0;
        String afterId = null;
        boolean exhausted = false;
        while (scanned < MAX_CANDIDATES && hits.size() <= limit) {
            int batchLimit = Math.min(CANDIDATE_BATCH, MAX_CANDIDATES - scanned);
            List<Source> batch = sources.candidates(owner, domain, status, query, afterId, batchLimit);
            if (batch.size() > batchLimit) throw new IllegalStateException("统一检索候选批次超过上限");
            for (Source source : batch) {
                String id = source.item().id();
                if (afterId != null && id.compareTo(afterId) >= 0) {
                    throw new IllegalStateException("统一检索候选顺序无效");
                }
                afterId = id;
                scanned++;
                Hit hit = located(source, query);
                if (hit.fragments().isEmpty()) continue;
                if (matched++ < offset) continue;
                hits.add(hit);
                if (hits.size() > limit) break;
            }
            if (batch.size() < batchLimit) {
                exhausted = true;
                break;
            }
        }
        // SQL 候选可能宽于最终字面匹配；分页只按白名单原文的有效命中计数。
        boolean extraHit = hits.size() > limit;
        boolean pageLimited = extraHit && offset + limit > MAX_OFFSET;
        boolean hasMore = extraHit && !pageLimited;
        boolean scanLimited = pageLimited || !extraHit && !exhausted && scanned >= MAX_CANDIDATES;
        return new Page(domain, query, status, MatchMode.LITERAL, true, null,
                List.copyOf(hits.subList(0, Math.min(hits.size(), limit))), limit, offset, hasMore, scanLimited);
    }

    private static Hit located(Source source, String query) {
        Hit hit = source.item();
        return new Hit(hit.domain(), hit.id(), hit.versionId(), hit.versionNumber(), hit.kind(), hit.title(),
                hit.status(), hit.currentVersion(), hit.verification(), hit.projectStatus(), hit.attachmentId(),
                hit.runId(), UnifiedSearchText.fragments(source.fields(), query),
                hit.training(), hit.feedback(), hit.recollection(), hit.aiFeedback());
    }

    private static Domain domain(String name) {
        if (name == null) throw invalid();
        try { return Domain.valueOf(name); }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }

    private static String query(String query) {
        if (query == null) throw invalid();
        String normalized = query.strip();
        if (normalized.isEmpty() || normalized.length() > 100) throw invalid();
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= normalized.length() || !Character.isLowSurrogate(normalized.charAt(index))) throw invalid();
            } else if (Character.isLowSurrogate(character)) throw invalid();
        }
        return normalized;
    }

    private static void uuid(String value) {
        if (value == null) throw invalid();
        try { if (!UUID.fromString(value).toString().equals(value)) throw invalid(); }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }

    private static ApiException invalid() {
        return new ApiException(400, "INVALID_REQUEST", "请检查检索词、来源、版本标识和分页范围。");
    }
}
