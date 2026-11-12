package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.knowledge.KnowledgeDtos.*;
import static com.wenjie.studio.knowledge.RelationSupplementDtos.*;
import static com.wenjie.studio.knowledge.RelationSupplementRows.*;

@Service
public class RelationSupplementService {
    private final RelationSupplementMapper mapper;
    private final IdempotencyService commands;

    public RelationSupplementService(RelationSupplementMapper mapper, IdempotencyService commands) {
        this.mapper = mapper;
        this.commands = commands;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Relation match(String owner, String sourceId, String targetId, RelationType type) {
        uuid(owner); uuid(sourceId); uuid(targetId);
        if (type == null || sourceId.equals(targetId)) throw invalid();
        boolean swap = symmetric(type) && sourceId.compareTo(targetId) > 0;
        RelationRow row = mapper.match(owner, swap ? targetId : sourceId, swap ? sourceId : targetId, type.name());
        return row == null ? null : relation(owner, row);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page page(String owner, String relationId, String cursor, int limit) {
        uuid(owner); uuid(relationId);
        if (cursor != null) uuid(cursor);
        if (limit < 1 || limit > 20) throw invalid();
        RelationRow live = mapper.relation(owner, relationId);
        Relation current = live == null ? null : relation(owner, live);
        if (current == null && !mapper.hasHistory(owner, relationId)) throw missing();
        List<EntryRow> rows = mapper.entries(owner, relationId, cursor, limit + 1);
        List<Supplement> values = new ArrayList<>();
        String previous = cursor;
        for (EntryRow row : rows.subList(0, Math.min(rows.size(), limit))) {
            Supplement value = view(owner, row);
            if (!relationId.equals(value.relationId()) || previous != null && value.id().compareTo(previous) <= 0
                    || current != null && !tuple(value).equals(current)) throw broken();
            values.add(value);
            previous = value.id();
        }
        return new Page(relationId, current != null, List.copyOf(values), rows.size() > limit ? values.getLast().id() : null);
    }

    public Supplement create(String owner, String relationId, String key, Create request) {
        uuid(owner); uuid(relationId);
        if (request == null || !Boolean.TRUE.equals(request.confirmed())) throw invalid();
        reason(request.reason()); links(request.sources(), false);
        // 当前边与来源仅约束首次追加；回执恢复依旧返回原结果，不重建已移除关系。
        return commands.execute(owner, key, "POST /api/v1/knowledge/relations/" + relationId + "/supplements",
                request, Supplement.class, () -> append(owner, currentRelation(owner, relationId), request.reason(),
                        request.sources(), Origin.MANUAL, null, null));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Relation requireActiveWithinCommand(String owner, String relationId) {
        // 直接原文证据复用同一条边的身份与端点规则；只在首次写命令中核当前性。
        requireWriteTransaction(); uuid(owner); uuid(relationId);
        return currentRelation(owner, relationId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Supplement supplementAiWithinCommand(String owner, Relation relation, String reason,
            List<SourceLink> sources, String taskId, String candidateId) {
        requireWriteTransaction(); uuid(owner); checkedTuple(relation); reason(reason); links(sources, true);
        uuid(taskId); candidateId(candidateId);
        Relation current = currentRelation(owner, relation.id());
        if (!current.equals(relation)) throw new ApiException(409, "RELATION_CHANGED", "所选关系与候选端点或类型不一致，请保留改稿并重新核对。");
        return append(owner, current, reason, sources, Origin.AI, taskId, candidateId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void verifyAiSupplement(String owner, String supplementId, Relation relation, String reason,
            List<SourceLink> sources, String taskId, String candidateId) {
        // 历史核验只核固定身份和版本；边被删除或知识改版不撤销已完成的补充决定。
        try {
            uuid(owner); uuid(supplementId); checkedTuple(relation); reason(reason); links(sources, true);
            uuid(taskId); candidateId(candidateId);
        } catch (ApiException exception) { throw broken(); }
        EntryRow row = mapper.entry(owner, supplementId);
        if (row == null) throw broken();
        Supplement saved = view(owner, row);
        if (saved.origin() != Origin.AI || !saved.id().equals(supplementId) || !tuple(saved).equals(relation)
                || !saved.reason().equals(reason) || !taskId.equals(saved.taskId()) || !candidateId.equals(saved.candidateId())
                || !saved.sources().stream().map(source -> new SourceLink(source.itemId(), source.versionId())).toList().equals(sources)) {
            throw broken();
        }
    }

    private Supplement append(String owner, Relation relation, String reason, List<SourceLink> links,
                              Origin origin, String taskId, String candidateId) {
        requireWriteTransaction();
        List<Source> sources = new ArrayList<>();
        for (SourceLink link : links) {
            SourceVersion version = mapper.sourceVersion(owner, link.itemId(), link.versionId());
            if (version == null || !"ACTIVE".equals(version.status()) || !link.versionId().equals(version.currentVersionId())) {
                throw new ApiException(409, "RELATION_SOURCE_CHANGED", "补充来源须为本人当前活跃知识版本，请保留输入后重新选择。");
            }
            if (!owner.equals(version.ownerId()) || !link.itemId().equals(version.itemId()) || !link.versionId().equals(version.versionId())
                    || version.number() < 1 || version.title() == null || !Set.of("NOTE", "CONCEPT").contains(version.kind())) throw broken();
            sources.add(new Source(version.itemId(), version.versionId(), version.number(), version.title(), Kind.valueOf(version.kind()), SourceState.CURRENT));
        }
        EntryRow row = new EntryRow(owner, UUID.randomUUID().toString(), relation.id(), relation.sourceId(), relation.targetId(),
                relation.type().name(), origin.name(), reason, sources.size(), taskId, candidateId, Instant.now().truncatedTo(ChronoUnit.MICROS));
        if (mapper.insertEntry(row) != 1) throw broken();
        for (int index = 0; index < sources.size(); index++) {
            if (mapper.insertSource(owner, row.id(), index + 1, sources.get(index)) != 1) throw broken();
        }
        return view(owner, row);
    }

    private Supplement view(String owner, EntryRow row) {
        try {
            if (row == null || !owner.equals(row.ownerId()) || row.createdAt() == null) throw broken();
            uuid(row.id());
            Relation relation = new Relation(row.relationId(), row.sourceId(), row.targetId(), RelationType.valueOf(row.type()));
            checkedTuple(relation); reason(row.reason());
            Origin origin = Origin.valueOf(row.origin());
            if (row.sourceCount() < (origin == Origin.AI ? 2 : 0) || row.sourceCount() > 5) throw broken();
            if (origin == Origin.AI) { uuid(row.taskId()); candidateId(row.candidateId()); }
            else if (row.taskId() != null || row.candidateId() != null) throw broken();
            List<SourceRow> recorded = mapper.sources(owner, row.id());
            if (recorded.size() != row.sourceCount()) throw broken();
            Set<String> itemIds = new HashSet<>();
            List<Source> sources = new ArrayList<>();
            for (int index = 0; index < recorded.size(); index++) {
                SourceRow source = recorded.get(index);
                if (!owner.equals(source.ownerId()) || !row.id().equals(source.supplementId()) || source.ordinal() != index + 1
                        || source.number() < 1 || source.title() == null || !itemIds.add(source.itemId())) throw broken();
                uuid(source.itemId()); uuid(source.versionId());
                Kind kind = Kind.valueOf(source.kind());
                SourceState state;
                if (source.actualNumber() == null || source.actualKind() == null) state = SourceState.UNAVAILABLE;
                else {
                    // 存储元数据必须对应当时确切版本；只允许当前性变化，不能用最新标题修补历史。
                    if (source.number() != source.actualNumber() || !source.title().equals(source.actualTitle())
                            || !source.kind().equals(source.actualKind()) || !Set.of("ACTIVE", "ARCHIVED").contains(source.actualStatus())) throw broken();
                    state = "ARCHIVED".equals(source.actualStatus()) ? SourceState.ARCHIVED
                            : source.versionId().equals(source.currentVersionId()) ? SourceState.CURRENT : SourceState.SUPERSEDED;
                }
                sources.add(new Source(source.itemId(), source.versionId(), source.number(), source.title(), kind, state));
            }
            return new Supplement(row.id(), relation.id(), relation.sourceId(), relation.targetId(), relation.type(), origin,
                    row.reason(), List.copyOf(sources), row.taskId(), row.candidateId(), row.createdAt());
        } catch (ApiException | IllegalArgumentException exception) { throw broken(); }
    }

    private Relation currentRelation(String owner, String relationId) {
        RelationRow row = mapper.relation(owner, relationId);
        if (row == null) throw missing();
        Relation relation = relation(owner, row);
        if (!"ACTIVE".equals(row.sourceStatus()) || !"ACTIVE".equals(row.targetStatus())) {
            throw new ApiException(409, "RELATION_ENDPOINT_ARCHIVED", "关系端点已归档，请恢复后再补充依据。");
        }
        return relation;
    }

    private static Relation relation(String owner, RelationRow row) {
        try {
            if (!owner.equals(row.ownerId()) || !"CONCEPT".equals(row.sourceKind()) || !"CONCEPT".equals(row.targetKind())) throw broken();
            Relation value = new Relation(row.id(), row.sourceId(), row.targetId(), RelationType.valueOf(row.type()));
            checkedTuple(value);
            return value;
        } catch (ApiException | IllegalArgumentException exception) { throw broken(); }
    }

    private static Relation tuple(Supplement value) { return new Relation(value.relationId(), value.sourceId(), value.targetId(), value.type()); }
    private static void checkedTuple(Relation value) {
        if (value == null || value.type() == null) throw invalid();
        uuid(value.id()); uuid(value.sourceId()); uuid(value.targetId());
        if (value.sourceId().equals(value.targetId()) || symmetric(value.type()) && value.sourceId().compareTo(value.targetId()) > 0) throw invalid();
    }
    private static boolean symmetric(RelationType type) { return type == RelationType.RELATED || type == RelationType.CONTRASTS; }
    private static void links(List<SourceLink> values, boolean ai) {
        if (values == null || values.size() < (ai ? 2 : 0) || values.size() > 5) throw invalid();
        Set<String> ids = new HashSet<>();
        for (SourceLink link : values) {
            if (link == null) throw invalid();
            uuid(link.itemId()); uuid(link.versionId());
            if (!ids.add(link.itemId())) throw invalid();
        }
    }
    private static void reason(String value) {
        if (value == null || value.isEmpty() || value.length() > 1000 || value.indexOf('\0') >= 0
                || value.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF)
                || value.codePoints().allMatch(point -> Character.isWhitespace(point) || Character.isSpaceChar(point))) throw invalid();
    }
    private static void candidateId(String value) { if (value == null || !value.matches("r([1-9]|10)")) throw invalid(); }
    private static void uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw invalid(); }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }
    private static void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("关系补充须在持有 owner 锁的写命令事务中执行");
        }
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "关系补充的身份、理由、来源或确认字段无效。"); }
    private static ApiException missing() { return new ApiException(404, "RELATION_NOT_FOUND", "关系不存在或不可访问。"); }
    private static IllegalStateException broken() { return new IllegalStateException("关系补充记录与固定身份或确切来源不一致"); }
}
