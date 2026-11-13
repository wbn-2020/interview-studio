package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.wenjie.studio.knowledge.KnowledgeGuards.*;

@Service
public class KnowledgeSourceService {
    private final KnowledgeSourceMapper mapper;
    private final KnowledgeMapper knowledge;
    private final IdempotencyService commands;
    private final JsonMapper json;

    public KnowledgeSourceService(KnowledgeSourceMapper mapper, KnowledgeMapper knowledge,
                                  IdempotencyService commands, JsonMapper json) {
        this.mapper = mapper;
        this.knowledge = knowledge;
        this.commands = commands;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public KnowledgeSourceDtos.Document get(String owner, String itemId, String versionId, Long revision) {
        requireVersion(owner, itemId, versionId);
        if (revision != null) { revision(revision); if (revision == 0) throw invalid("历史来源修订从 1 开始。"); }
        var row = mapper.find(owner, versionId, revision);
        if (row == null && revision != null) throw new ApiException(404, "SOURCE_REVISION_NOT_FOUND", "来源修订不存在。");
        return document(itemId, versionId, row);
    }

    public KnowledgeSourceDtos.Document save(String owner, String itemId, String versionId,
                                              String key, KnowledgeSourceDtos.Save request) {
        uuid(itemId); uuid(versionId); required(request); revision(request.expectedRevision());
        if (request.sources() == null || request.sources().size() > 20) throw invalid("每个知识版本最多记录 20 项来源。");
        List<KnowledgeSourceDtos.Source> sources = request.sources().stream().map(this::validate).toList();
        return commands.execute(owner, key, "PUT /api/v1/knowledge/items/" + itemId + "/versions/" + versionId + "/sources",
                request, KnowledgeSourceDtos.Document.class, () -> {
                    requireVersion(owner, itemId, versionId);
                    var item = knowledge.findItem(owner, itemId);
                    if (item == null) throw new ApiException(404, "KNOWLEDGE_NOT_FOUND", "知识内容不存在或不可访问。");
                    if (!item.status().equals("ACTIVE")) throw new ApiException(409, "KNOWLEDGE_ARCHIVED", "恢复知识后再补充来源。");
                    if (!versionId.equals(item.currentVersionId())) {
                        throw new ApiException(409, "KNOWLEDGE_SOURCE_VERSION_CHANGED", "知识已经发布新版本，请回到当前发布版补充来源；旧版来源记录继续保留。");
                    }
                    var before = mapper.find(owner, versionId, null);
                    long current = before == null ? 0 : before.revision();
                    if (current != request.expectedRevision()) throw new ApiException(409, "REVISION_CONFLICT", "来源记录已变化，请保留输入后重新读取。", current);
                    if (current == MAX_REVISION) throw new ApiException(422, "CAPACITY_LIMIT", "来源修订号已达上限。");
                    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                    // owner 锁覆盖当前发布版及独立来源 CAS；不推进正文、草稿、审阅或模型授权基线。
                    mapper.insert(owner, itemId, versionId, current + 1, json.writeValueAsString(sources), now);
                    return new KnowledgeSourceDtos.Document(itemId, versionId, current + 1, sources, now);
                });
    }

    private void requireVersion(String owner, String itemId, String versionId) {
        uuid(owner); uuid(itemId); uuid(versionId);
        if (knowledge.findVersion(owner, itemId, versionId) == null) throw new ApiException(404, "KNOWLEDGE_VERSION_NOT_FOUND", "知识版本不存在或不可访问。");
    }

    private KnowledgeSourceDtos.Document document(String itemId, String versionId, KnowledgeSourceDtos.Row row) {
        return row == null ? new KnowledgeSourceDtos.Document(itemId, versionId, 0, List.of(), null)
                : new KnowledgeSourceDtos.Document(itemId, versionId, row.revision(),
                List.of(json.readValue(row.sourcesJson(), KnowledgeSourceDtos.Source[].class)), row.savedAt());
    }

    private KnowledgeSourceDtos.Source validate(KnowledgeSourceDtos.Source source) {
        required(source);
        String title = text(source.title(), 200, true);
        String url = text(source.url(), 2000, false);
        if (!url.isEmpty()) {
            try {
                URI parsed = URI.create(url);
                if (!List.of("https", "http").contains(parsed.getScheme()) || parsed.getHost() == null
                        || parsed.getRawUserInfo() != null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) { throw invalid("来源地址仅支持不含账号信息的 HTTP／HTTPS 链接。"); }
        }
        Instant acquired = source.acquiredAt();
        if (acquired != null && (acquired.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                || acquired.isAfter(Instant.now().plusSeconds(300)) || acquired.getNano() % 1000 != 0)) {
            throw invalid("获取时间必须是有效的历史时间，精度最多为微秒。");
        }
        return new KnowledgeSourceDtos.Source(title, url, text(source.author(), 200, false), acquired,
                text(source.applicableVersion(), 200, false), text(source.rights(), 2000, false), text(source.note(), 4000, false));
    }

    private static String text(String value, int limit, boolean required) {
        String result = value == null ? "" : value.strip();
        if (result.length() > limit || required && result.isEmpty() || result.indexOf('\0') >= 0)
            throw invalid("请检查来源标题及字段长度。");
        return result;
    }
}
