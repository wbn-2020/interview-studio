package com.wenjie.studio.memory;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.wenjie.studio.memory.MemoryDtos.*;
import static com.wenjie.studio.memory.MemoryRows.*;
import static com.wenjie.studio.memory.MemoryRules.*;

@Service
public class MemoryService {
    private final MemoryMapper mapper;
    private final IdempotencyService commands;
    private final MemorySources sources;
    private final MemoryInvalidation invalidation;
    private final JsonMapper json;

    public MemoryService(MemoryMapper mapper, IdempotencyService commands, MemorySources sources, MemoryInvalidation invalidation, JsonMapper json) {
        this.mapper = mapper;
        this.commands = commands;
        this.sources = sources;
        this.invalidation = invalidation;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(String owner, Status status, Kind kind, String query, String sourceItemId,
                     String relatedConceptId, String cursor, int limit) {
        uuid(owner); required(status); page(limit, 50);
        if (sourceItemId != null) uuid(sourceItemId);
        if (relatedConceptId != null) uuid(relatedConceptId);
        if (cursor != null) uuid(cursor);
        List<SummaryRow> rows = mapper.cards(owner, status.name(), kind == null ? null : kind.name(), pattern(query),
                sourceItemId, relatedConceptId, cursor, limit + 1);
        boolean more = rows.size() > limit;
        List<Summary> items = rows.stream().limit(limit).map(row -> new Summary(row.id(), Kind.valueOf(row.kind()),
                row.title(), Status.valueOf(row.status()), row.revision(), row.versionId(), row.versionNumber(),
                row.sourceCount(), row.relatedConceptCount(), row.updatedAt())).toList();
        return new Page(items, more ? items.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Card detail(String owner, String id) {
        return card(owner, requireCard(owner, id));
    }

    public Card create(String owner, String key, Create request) {
        required(request); required(request.content());
        return commands.execute(owner, key, "POST /api/v1/memory/cards", request, Card.class,
                () -> createWithinCommand(owner, request));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Card createWithinCommand(String owner, Create request) {
        uuid(owner); required(request); required(request.content());
        // 调用方须已取得同一 owner 命令锁，使卡片、来源、观察基线和回执原子提交。
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("创建回忆卡必须加入已取得 owner 锁的写事务");
        }
        Content content = request.content();
        int bytes = content(json, content);
        sources.validate(owner, content, null);
        String id = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        Instant now = now();
        mapper.insertCard(new CardRow(id, owner, Status.ACTIVE.name(), 0, versionId, 1, now, now));
        insertVersion(owner, id, versionId, 1, content, bytes, now);
        invalidation.initializeCard(owner, id, versionId, now);
        return card(owner, requireCard(owner, id));
    }

    public Card save(String owner, String id, String key, Save request) {
        uuid(id); required(request); revision(request.expectedRevision()); required(request.content());
        return commands.execute(owner, key, "PUT /api/v1/memory/cards/" + id, request, Card.class, () -> {
            CardRow row = requireCard(owner, id);
            expected(row, request.expectedRevision());
            if (!Status.ACTIVE.name().equals(row.status())) {
                throw new ApiException(409, "ITEM_ARCHIVED", "请先恢复回忆卡，再修改题面与参考。");
            }
            canAdvance(row);
            if (row.versionNumber() >= MAX_VERSION) throw revisionLimit();
            Content content = request.content();
            int bytes = content(json, content);
            Prompt previous = readVersion(owner, id, row.currentVersionId()).content();
            sources.validate(owner, content, previous);
            String versionId = UUID.randomUUID().toString();
            int number = row.versionNumber() + 1;
            Instant now = now();
            insertVersion(owner, id, versionId, number, content, bytes, now);
            changed(mapper.saveContent(owner, id, row.revision(), versionId, number, now), row.revision());
            invalidation.cardVersionChanged(owner, id, row.currentVersionId(), versionId, row.revision() + 1, now);
            return card(owner, requireCard(owner, id));
        });
    }

    public Card status(String owner, String id, String key, ChangeStatus request, Status status) {
        uuid(id); required(request); revision(request.expectedRevision()); required(status);
        String operation = "POST /api/v1/memory/cards/" + id + (status == Status.ARCHIVED ? "/archive" : "/restore");
        return commands.execute(owner, key, operation, request, Card.class, () -> {
            CardRow row = requireCard(owner, id);
            expected(row, request.expectedRevision());
            // 同状态命令先校验 CAS 后返回；归档与恢复均不创建内容版本或复习记录。
            if (status.name().equals(row.status())) return card(owner, row);
            canAdvance(row);
            Instant now = now();
            changed(mapper.changeStatus(owner, id, row.revision(), status.name(), now), row.revision());
            invalidation.cardStatusChanged(owner, id, row.currentVersionId(), row.revision() + 1, Status.valueOf(row.status()), status, now);
            return card(owner, requireCard(owner, id));
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VersionPage versions(String owner, String id, Integer before, int limit) {
        requireCard(owner, id); page(limit, 20);
        if (before != null && (before < 1 || before > MAX_VERSION + 1)) throw invalid("版本游标须为 1 至 1001 的整数。");
        List<VersionSummary> rows = mapper.versions(owner, id, before, limit + 1);
        boolean more = rows.size() > limit;
        List<VersionSummary> items = rows.stream().limit(limit).toList();
        return new VersionPage(items, more ? items.getLast().number() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VersionDetail version(String owner, String id, String versionId) {
        requireCard(owner, id); uuid(versionId);
        Version version = readVersion(owner, id, versionId);
        return new VersionDetail(version, sources.references(owner, version.content()), sources.concepts(owner, version.content()));
    }

    public Reference reference(String owner, String id, String versionId, String key, EmptyRequest request) {
        uuid(id); uuid(versionId); required(request);
        String operation = "POST /api/v1/memory/cards/" + id + "/versions/" + versionId + "/reference";
        return commands.execute(owner, key, operation, request, Reference.class, () -> {
            requireCard(owner, id);
            ReferenceRow row = mapper.reference(owner, id, versionId);
            if (row == null) throw missing();
            // 此回执仅记录确切版本参考的读取结果，不作为正式复习的 revealed 或评分凭据。
            return new Reference(id, versionId, row.versionNumber(), row.referenceText());
        });
    }

    private Card card(String owner, CardRow row) {
        Version version = readVersion(owner, row.id(), row.currentVersionId());
        return new Card(row.id(), Status.valueOf(row.status()), row.revision(), version,
                sources.references(owner, version.content()), sources.concepts(owner, version.content()), row.createdAt(), row.updatedAt());
    }

    private CardRow requireCard(String owner, String id) {
        uuid(owner); uuid(id);
        CardRow row = mapper.card(owner, id);
        if (row == null) throw missing();
        return row;
    }

    private Version readVersion(String owner, String id, String versionId) {
        VersionRow row = mapper.version(owner, id, versionId);
        if (row == null) throw missing();
        return new Version(row.id(), row.versionNumber(), json.readValue(row.promptJson(), Prompt.class), row.createdAt());
    }

    private void insertVersion(String owner, String id, String versionId, int number, Content content, int bytes, Instant now) {
        Prompt prompt = new Prompt(content.kind(), content.title(), content.prompt(), content.sources(), content.relatedConceptIds());
        mapper.insertVersion(new VersionRow(versionId, id, owner, number, content.kind().name(), content.title(),
                json.writeValueAsString(prompt), now), content.reference(), bytes, content.sources().size(), content.relatedConceptIds().size());
        for (int i = 0; i < content.sources().size(); i++) {
            SourceLink source = content.sources().get(i);
            mapper.insertSource(owner, id, versionId, source.itemId(), source.versionId(), i);
        }
        for (int i = 0; i < content.relatedConceptIds().size(); i++) {
            mapper.insertConcept(owner, id, versionId, content.relatedConceptIds().get(i), i);
        }
    }

    private void expected(CardRow row, long expected) {
        if (row.revision() != expected) throw conflict(row.revision());
    }

    private void canAdvance(CardRow row) { if (row.revision() >= MAX_REVISION) throw revisionLimit(); }
    private void changed(int count, long revision) { if (count != 1) throw conflict(revision); }
    private ApiException conflict(long revision) {
        return new ApiException(409, "REVISION_CONFLICT", "回忆卡已发生变化，请保留输入并重新读取。", revision);
    }
}
