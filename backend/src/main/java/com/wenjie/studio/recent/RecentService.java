package com.wenjie.studio.recent;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.knowledge.OrganizationMapper;
import com.wenjie.studio.material.MaterialProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.wenjie.studio.recent.RecentDtos.*;

@Service
public class RecentService {
    private static final int SCAN_LIMIT = 64;
    private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");
    private final RecentMapper mapper;
    private final OrganizationMapper organization;
    private final IdempotencyService commands;
    private final MaterialProperties materials;

    public RecentService(RecentMapper mapper, OrganizationMapper organization,
                          IdempotencyService commands, MaterialProperties materials) {
        this.mapper = mapper;
        this.organization = organization;
        this.commands = commands;
        this.materials = materials;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public Page list(String owner, Kind filter, String cursor, int limit) {
        uuid(owner);
        if (limit < 1 || limit > 20) throw invalid("每页数量须为 1 至 20。");
        RecentRows.VisitRow after = cursor(cursor, filter);
        var rows = mapper.visits(owner, filter == null ? null : filter.name(),
                after == null ? null : after.visitedAt(), after == null ? null : after.kind(),
                after == null ? null : after.id(), SCAN_LIMIT);
        if (rows.size() > SCAN_LIMIT) throw new IllegalStateException("最近访问扫描超出上限");
        var entries = new ArrayList<Entry>();
        RecentRows.VisitRow last = null;
        int scanned = 0;
        for (var row : rows) {
            last = row;
            scanned++;
            Kind kind = Kind.valueOf(row.kind());
            if (kind == Kind.MATERIAL && !materials.enabled()) continue;
            // 目录只读取当前窄元数据；失效索引可跳过，数据库故障必须原样失败。
            var metadata = mapper.metadata(owner, row.kind(), row.id());
            if (metadata == null) continue;
            entries.add(new Entry(kind, row.id(), metadata.title(), metadata.subtype(), metadata.status(), row.visitedAt()));
            if (entries.size() == limit) break;
        }
        // 页满时尚未检查的行留给下一页；满 64 行时保留继续游标，避免猜测后续为空。
        boolean more = scanned < rows.size() || rows.size() == SCAN_LIMIT;
        String next = more && last != null ? last.visitedAt() + "~" + last.kind() + "~" + last.id() : null;
        return new Page(List.copyOf(entries), next, scanned == SCAN_LIMIT && entries.size() < limit);
    }

    public Visit visit(String owner, String key, VisitRequest request) {
        uuid(owner);
        if (request == null || request.kind() == null) throw invalid("访问类型与对象标识不能为空。");
        uuid(request.id());
        // 首次写入及原键恢复均在 owner 锁内核对当前对象与资料条件，回执不复制对象标题。
        return commands.execute(owner, key, "POST /api/v1/recent/visits", request, Visit.class,
                () -> requireTarget(owner, request.kind(), request.id()),
                () -> new Visit(request.kind(), request.id(), record(owner, request.kind(), request.id())));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Instant recordKnowledge(String owner, String id) {
        // 旧知识命令持有相同 owner 锁；两份访问时间与旧回执必须共同提交。
        uuid(owner);
        uuid(id);
        requireTarget(owner, Kind.KNOWLEDGE, id);
        return record(owner, Kind.KNOWLEDGE, id);
    }

    private void requireTarget(String owner, Kind kind, String id) {
        if (kind == Kind.MATERIAL && !materials.enabled()) {
            throw new ApiException(503, "MATERIALS_DISABLED", "服务器尚未启用附件资料。");
        }
        if (mapper.metadata(owner, kind.name(), id) == null) {
            throw new ApiException(404, "RECENT_TARGET_NOT_FOUND", "访问对象不存在或不可访问。");
        }
    }

    private Instant record(String owner, Kind kind, String id) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant previous = mapper.visitTime(owner, kind.name(), id);
        if (previous != null && previous.isAfter(now)) now = previous;
        if (kind == Kind.KNOWLEDGE) {
            Instant knowledgeTime = mapper.knowledgeVisitTime(owner, id);
            if (knowledgeTime != null && knowledgeTime.isAfter(now)) now = knowledgeTime;
            organization.ensureOrganization(owner, id, now);
            organization.visit(owner, id, now);
        }
        // 服务时钟回拨不能让对象排序倒退；访问不会递增业务修订或发出学习、曝光与索引事件。
        mapper.upsertVisit(owner, kind.name(), id, now);
        return now;
    }

    private static RecentRows.VisitRow cursor(String value, Kind filter) {
        if (value == null) return null;
        if (value.length() > 100) throw invalid("最近访问游标无效。");
        String[] parts = value.split("~", -1);
        if (parts.length != 3) throw invalid("最近访问游标无效。");
        Kind kind = kind(parts[1]);
        if (filter != null && filter != kind) throw invalid("游标类型与筛选条件不一致。");
        uuid(parts[2]);
        try {
            Instant time = Instant.parse(parts[0]);
            if (!time.toString().equals(parts[0]) || time.getNano() % 1000 != 0
                    || time.isBefore(MIN_TIME) || time.isAfter(MAX_TIME)) throw invalid("最近访问游标无效。");
            return new RecentRows.VisitRow(kind.name(), parts[2], time);
        } catch (DateTimeParseException exception) { throw invalid("最近访问游标无效。"); }
    }

    static Kind kind(String value) {
        try {
            if (value == null) throw new IllegalArgumentException();
            return Kind.valueOf(value);
        } catch (IllegalArgumentException exception) { throw invalid("最近访问类型无效。"); }
    }

    private static void uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) { throw invalid("对象标识须为标准小写 UUID。"); }
    }

    static ApiException invalid(String message) {
        return new ApiException(400, "INVALID_RECENT_REQUEST", message);
    }
}
