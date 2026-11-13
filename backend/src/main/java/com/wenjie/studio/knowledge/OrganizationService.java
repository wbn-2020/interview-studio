package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.knowledge.KnowledgeDtos.*;
import com.wenjie.studio.knowledge.OrganizationDtos.*;
import com.wenjie.studio.knowledge.OrganizationDtos.Collection;
import com.wenjie.studio.knowledge.OrganizationRows.OrganizationRow;
import com.wenjie.studio.recent.RecentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.knowledge.KnowledgeGuards.*;

@Service
public class OrganizationService {
    private final OrganizationMapper mapper;
    private final KnowledgeMapper knowledge;
    private final IdempotencyService commands;
    private final JsonMapper json;
    private final RecentService recent;

    public OrganizationService(OrganizationMapper mapper, KnowledgeMapper knowledge,
                               IdempotencyService commands, JsonMapper json, RecentService recent) {
        this.mapper = mapper;
        this.knowledge = knowledge;
        this.commands = commands;
        this.json = json;
        this.recent = recent;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Organization get(String owner, String id) {
        requireItem(owner, uuid(id));
        return current(owner, id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Organization initializeImportedTagsWithinCommand(String owner, String id, List<String> tags) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("导入标签必须处于命令写事务中");
        }
        uuid(owner); uuid(id);
        List<String> selected = labels(tags, 40, "标签");
        if (selected.isEmpty()) throw invalid("明确采用的标签不能为空。");
        var item = requireItem(owner, id);
        // 仅供外层导入命令初始化刚创建的笔记，绝不能覆写已有整理信息或扩展概念别名资格。
        if (!item.kind().equals("NOTE") || !item.status().equals("ACTIVE")
                || item.versionNumber() != 1 || item.metadataRevision() != 0
                || mapper.organization(owner, id) != null || !mapper.associations(owner, id).isEmpty()) {
            throw new ApiException(409, "REVISION_CONFLICT", "导入标签仅能初始化新笔记，不能覆盖已有整理信息。");
        }
        Instant now = now();
        mapper.ensureOrganization(owner, id, now);
        int changed = mapper.saveOrganization(owner, id, 0, json.writeValueAsString(List.of()),
                json.writeValueAsString(selected), false, null, now);
        if (changed != 1) throw new ApiException(409, "REVISION_CONFLICT", "整理信息已变化，本次导入未提交。");
        knowledge.insertOutbox(owner, id, item.currentVersionId(), "METADATA", now);
        return current(owner, id);
    }

    public Organization save(String owner, String id, String key, OrganizationRequest request) {
        validateOrganizationRequest(id, request);
        return commands.execute(owner, key, "PUT /api/v1/knowledge/items/" + id + "/organization", request,
                Organization.class, () -> saveWithinCommand(owner, id, request));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Organization saveWithinCommand(String owner, String id, OrganizationRequest request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("整理信息必须处于命令写事务中");
        }
        validateOrganizationRequest(id, request);
        List<String> aliases = labels(request.aliases(), 100, "别名");
        List<String> tags = labels(request.tags(), 40, "标签");
        List<String> noteIds = ids(request.noteIds());
        var item = requireItem(owner, id);
        if (!item.status().equals("ACTIVE")) throw new ApiException(409, "KNOWLEDGE_ARCHIVED", "恢复内容后才能修改整理信息。");
        if (item.kind().equals("NOTE") && (!aliases.isEmpty() || !noteIds.isEmpty() || request.mainNoteId() != null)) {
            throw invalid("笔记不能设置概念别名、关联笔记或主笔记。");
        }
        Organization before = current(owner, id);
        if (before.revision() != request.expectedRevision()) {
            throw new ApiException(409, "REVISION_CONFLICT", "整理信息已变化，请保留输入后重新读取。", before.revision());
        }
        if (before.revision() == MAX_REVISION) throw new ApiException(422, "CAPACITY_LIMIT", "整理修订号已达上限。");
        var associations = mapper.associations(owner, id);
        Set<String> existingIds = new HashSet<>(before.noteIds());
        for (String noteId : noteIds) {
            var note = requireItem(owner, noteId);
            if (!note.kind().equals("NOTE")) throw invalid("概念只能关联笔记。");
            // 已归档关联可保留，新增关联必须指向当前活跃笔记，正文始终互不复制。
            if (!note.status().equals("ACTIVE") && !existingIds.contains(noteId)) {
                throw new ApiException(409, "NOTE_ARCHIVED", "不能新增关联已归档笔记。");
            }
        }
        Instant now = now();
        mapper.ensureOrganization(owner, id, now);
        int changed = mapper.saveOrganization(owner, id, before.revision(), json.writeValueAsString(aliases),
                json.writeValueAsString(tags), request.favorite(), request.mainNoteId(), now);
        if (changed != 1) throw new ApiException(409, "REVISION_CONFLICT", "整理信息已变化，本次修改未提交。");
        for (var link : associations) if (!noteIds.contains(link.noteId())) mapper.removeAssociation(owner, link.id());
        for (String noteId : noteIds) {
            if (!existingIds.contains(noteId)) mapper.insertAssociation(owner, UUID.randomUUID().toString(), id, noteId, now);
        }
        knowledge.insertOutbox(owner, id, item.currentVersionId(), "METADATA", now);
        return current(owner, id);
    }

    private static void validateOrganizationRequest(String id, OrganizationRequest request) {
        uuid(id);
        required(request);
        revision(request.expectedRevision());
        labels(request.aliases(), 100, "别名");
        labels(request.tags(), 40, "标签");
        required(request.favorite());
        List<String> noteIds = ids(request.noteIds());
        if (request.mainNoteId() != null) {
            uuid(request.mainNoteId());
            if (!noteIds.contains(request.mainNoteId())) throw invalid("主笔记必须属于已关联笔记。");
        }
    }

    public VisitResult visit(String owner, String id, String key, EmptyRequest request) {
        uuid(id);
        required(request);
        return commands.execute(owner, key, "POST /api/v1/knowledge/items/" + id + "/visit", request,
                VisitResult.class, () -> requireItem(owner, id),
                // 保留旧回执形状；知识 RECENT 与全局目录在原命令事务内使用同一访问时间。
                () -> new VisitResult(recent.recordKnowledge(owner, id)));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConnectionPage connections(String owner, String id, ConnectionKind type, String cursor, int limit) {
        requireItem(owner, uuid(id));
        required(type);
        if (cursor != null) uuid(cursor);
        pageLimit(limit);
        var rows = mapper.connections(owner, id, type.name(), cursor, limit + 1);
        boolean hasMore = rows.size() > limit;
        var page = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        return new ConnectionPage(page, hasMore ? page.getLast().id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ItemPage library(String owner, Collection collection, Kind kind, Status status,
                             String query, String cursor, int limit) {
        required(collection);
        required(status);
        pageLimit(limit);
        if (query != null && query.length() > 100) throw invalid("标题筛选不能超过 100 个字符。");
        String pattern = query == null || query.isEmpty() ? null : "%" + query.replace("!", "!!")
                .replace("%", "!%").replace("_", "!_") + "%";
        String cursorId = null;
        Instant cursorTime = null;
        if (cursor != null) {
            if (collection == Collection.RECENT) {
                String[] parts = cursor.split("~", -1);
                if (parts.length != 2) throw invalid("最近访问游标无效。");
                cursorId = uuid(parts[1]);
                try {
                    cursorTime = Instant.parse(parts[0]);
                    if (!cursorTime.toString().equals(parts[0]) || cursorTime.getNano() % 1000 != 0
                            || cursorTime.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                            || cursorTime.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) throw invalid("最近访问游标无效。");
                } catch (DateTimeParseException exception) { throw invalid("最近访问游标无效。"); }
            } else cursorId = uuid(cursor);
        }
        var rows = mapper.library(owner, collection.name(), kind == null ? null : kind.name(), status.name(),
                pattern, cursorId, cursorTime, limit + 1);
        boolean more = rows.size() > limit;
        var page = rows.subList(0, Math.min(rows.size(), limit));
        List<String> ids = page.stream().map(OrganizationRows.LibraryItem::id).toList();
        Map<String, List<String>> topics = new HashMap<>();
        if (!ids.isEmpty()) for (var link : knowledge.topicLinks(owner, ids)) {
            topics.computeIfAbsent(link.itemId(), ignored -> new ArrayList<>()).add(link.topicId());
        }
        var items = page.stream().map(item -> new ItemSummary(item.id(), Kind.valueOf(item.kind()), item.title(),
                Status.valueOf(item.status()), item.revision(), item.versionNumber(), item.updatedAt(),
                List.copyOf(topics.getOrDefault(item.id(), List.of())))).toList();
        String next = null;
        if (more) {
            var last = page.getLast();
            next = collection == Collection.RECENT ? last.visitedAt() + "~" + last.id() : last.id();
        }
        return new ItemPage(items, next);
    }

    private Organization current(String owner, String id) {
        OrganizationRow row = mapper.organization(owner, id);
        var links = mapper.associations(owner, id);
        if (links.size() > 100) throw new ApiException(422, "CAPACITY_LIMIT", "笔记关联超出读取边界。");
        var noteIds = links.stream().map(OrganizationRows.Association::noteId).toList();
        if (row == null) return new Organization(0, List.of(), List.of(), false, noteIds, null, null);
        return new Organization(row.revision(), List.of(json.readValue(row.aliasesJson(), String[].class)),
                List.of(json.readValue(row.tagsJson(), String[].class)), row.favorite(), noteIds,
                row.mainNoteId(), row.visitedAt());
    }

    private KnowledgeRows.Item requireItem(String owner, String id) {
        var item = knowledge.findItem(owner, id);
        if (item == null) throw new ApiException(404, "KNOWLEDGE_NOT_FOUND", "知识内容不存在或不可访问。");
        return item;
    }

    private static List<String> labels(List<String> values, int length, String label) {
        if (values == null || values.size() > 20) throw invalid(label + "列表不能为空值，最多 20 项。");
        Map<String, String> unique = new LinkedHashMap<>();
        for (String value : values) {
            if (value == null || value.strip().isEmpty() || value.strip().length() > length) {
                throw invalid(label + "每项长度必须为 1 至 " + length + " 字符。");
            }
            String trimmed = value.strip();
            unique.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
        }
        return List.copyOf(unique.values());
    }

    private static List<String> ids(List<String> values) {
        if (values == null || values.size() > 100) throw invalid("关联笔记最多 100 篇，需提供列表。");
        Set<String> seen = new HashSet<>();
        for (String value : values) if (!seen.add(uuid(value))) throw invalid("不能重复关联同一笔记。");
        return List.copyOf(values);
    }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
}
