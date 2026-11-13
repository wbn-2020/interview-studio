package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static com.wenjie.studio.knowledge.KnowledgeGuards.*;
import static com.wenjie.studio.knowledge.KnowledgeTextImportBatchDtos.*;
import static com.wenjie.studio.knowledge.KnowledgeTextImportBatchMapper.*;

@Service
public class KnowledgeTextImportBatchService {
    private final KnowledgeTextImportBatchMapper mapper;
    private final KnowledgeTextImportMapper imports;
    private final KnowledgeTextImportService textImports;
    private final IdempotencyService commands;
    private final KnowledgeService knowledge;

    public KnowledgeTextImportBatchService(KnowledgeTextImportBatchMapper mapper, KnowledgeTextImportMapper imports,
                                           KnowledgeTextImportService textImports, IdempotencyService commands,
                                           KnowledgeService knowledge) {
        this.mapper = mapper; this.imports = imports; this.textImports = textImports; this.commands = commands;
        this.knowledge = knowledge;
    }

    public Detail create(String owner, String key, CreateBatch request) {
        required(request);
        if (request.files() == null || request.files().isEmpty() || request.files().size() > 20) {
            throw invalid("一个导入批次须包含1至20份文件。");
        }
        var names = new HashSet<String>();
        for (FileEntry file : request.files()) {
            required(file);
            KnowledgeTextImportService.fileName(file.fileName());
            if (!names.add(file.fileName())) throw invalid("批次内不能包含完全相同的文件名。");
            if (file.sourceSha256() == null || !file.sourceSha256().matches("[0-9a-f]{64}")
                    || file.sourceBytes() == null || file.sourceBytes() < 1 || file.sourceBytes() > 400_000
                    || file.sourceCharacters() == null || file.sourceCharacters() < 1 || file.sourceCharacters() > 100_000) {
                throw invalid("文件摘要、字节数或字符数无效。");
            }
        }
        return commands.execute(owner, key, "POST /api/v1/knowledge/text-import-batches", request, Detail.class, () -> {
            BatchRow batch = new BatchRow(UUID.randomUUID().toString(), 0, request.files().size(),
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
            mapper.insertBatch(owner, batch);
            for (int index = 0; index < request.files().size(); index++) {
                FileEntry file = request.files().get(index);
                mapper.insertEntry(owner, batch.id(), new EntryRow(UUID.randomUUID().toString(), index + 1,
                        file.fileName(), file.sourceSha256(), file.sourceBytes(), file.sourceCharacters(),
                        "PENDING", false, null, file.fileName()));
            }
            // 清单仅是用户声明的候选身份；提交某一项时才核原文，不以登记清单认证内容。
            return snapshot(owner, batch);
        });
    }

    public DirectoryDetail createDirectory(String owner, String key, CreateDirectoryBatch request) {
        required(request);
        if (request.files() == null || request.files().isEmpty() || request.files().size() > 20) {
            throw invalid("一个导入批次须包含1至20份文件。");
        }
        var paths = new HashSet<String>();
        for (DirectoryFile file : request.files()) {
            required(file);
            KnowledgeTextImportService.fileName(file.fileName());
            sourcePath(file.sourcePath(), file.fileName());
            if (!paths.add(file.sourcePath())) throw invalid("批次内不能包含完全相同的相对路径。");
            if (file.sourceSha256() == null || !file.sourceSha256().matches("[0-9a-f]{64}")
                    || file.sourceBytes() == null || file.sourceBytes() < 1 || file.sourceBytes() > 400_000
                    || file.sourceCharacters() == null || file.sourceCharacters() < 1 || file.sourceCharacters() > 100_000) {
                throw invalid("文件摘要、字节数或字符数无效。");
            }
        }
        return commands.execute(owner, key, "POST /api/v1/knowledge/text-import-batches/directories",
                request, DirectoryDetail.class, () -> {
                    BatchRow batch = new BatchRow(UUID.randomUUID().toString(), 0, request.files().size(),
                            Instant.now().truncatedTo(ChronoUnit.MICROS));
                    mapper.insertBatch(owner, batch);
                    for (int index = 0; index < request.files().size(); index++) {
                        DirectoryFile file = request.files().get(index);
                        mapper.insertEntry(owner, batch.id(), new EntryRow(UUID.randomUUID().toString(), index + 1,
                                file.fileName(), file.sourceSha256(), file.sourceBytes(), file.sourceCharacters(),
                                "PENDING", false, null, file.sourcePath()));
                    }
                    // 路径仅是用户提供的清单元数据；不访问服务器文件，也不授予证据或AI使用权限。
                    return directorySnapshot(owner, batch);
                });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(String owner, String id) {
        uuid(owner); uuid(id);
        return snapshot(owner, find(owner, id));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DirectoryDetail directoryDetail(String owner, String id) {
        uuid(owner); uuid(id);
        return directorySnapshot(owner, find(owner, id));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(String owner, String cursor, int limit) {
        uuid(owner); pageLimit(limit);
        BatchRow before = cursor == null ? null : mapper.find(owner, uuid(cursor));
        if (cursor != null && before == null) throw invalid("批次游标不存在或不属于当前工作区，请重新读取首页。");
        List<Summary> rows = mapper.list(owner, before == null ? null : before.id(),
                before == null ? null : before.createdAt(), limit + 1);
        boolean more = rows.size() > limit;
        List<Summary> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new Page(items, more ? items.getLast().id() : null);
    }

    public Detail importEntry(String owner, String key, String id, String entryId, ImportEntry request) {
        uuid(id); uuid(entryId); required(request); revision(request.revision());
        KnowledgeTextImportService.SourceIdentity identity = KnowledgeTextImportService.sourceIdentity(request.source());
        String operation = "POST /api/v1/knowledge/text-import-batches/" + id + "/entries/" + entryId + "/import";
        return commands.execute(owner, key, operation, request, Detail.class, () -> {
            BatchRow batch = find(owner, id);
            EntryRow entry = pending(owner, batch, entryId, request.revision());
            if (!identity.sha256().equals(entry.sourceSha256()) || identity.bytes() != entry.sourceBytes()
                    || identity.characters() != entry.sourceCharacters()) {
                throw new ApiException(409, "TEXT_IMPORT_SOURCE_MISMATCH",
                        "原始文本与批次清单不一致。请重新选择登记时的原文件，不会创建或覆盖任何知识。");
            }
            // 同一个owner锁内复用单份创建；首版、导入记录、批次决策及本次回执必须原子提交。
            var saved = textImports.createWithinCommand(owner, new KnowledgeTextImportDtos.Create(entry.fileName(),
                    request.source(), request.title(), request.body(), request.topicIds(), request.createCopy()));
            resolve(owner, batch, entryId, "IMPORTED", saved.record().id());
            return snapshot(owner, new BatchRow(batch.id(), batch.revision() + 1, batch.total(), batch.createdAt()));
        });
    }

    public Detail skipEntry(String owner, String key, String id, String entryId, SkipEntry request) {
        uuid(id); uuid(entryId); required(request); revision(request.revision());
        String operation = "POST /api/v1/knowledge/text-import-batches/" + id + "/entries/" + entryId + "/skip";
        return commands.execute(owner, key, operation, request, Detail.class, () -> {
            BatchRow batch = find(owner, id);
            pending(owner, batch, entryId, request.revision());
            resolve(owner, batch, entryId, "SKIPPED", null);
            return snapshot(owner, new BatchRow(batch.id(), batch.revision() + 1, batch.total(), batch.createdAt()));
        });
    }

    public KnowledgeDtos.ItemDetail publishLinks(String owner, String key, String id, String entryId,
                                                  PublishLinks request) {
        uuid(id); uuid(entryId); required(request); uuid(request.expectedVersionId());
        revision(request.expectedDraftRevision());
        // 这里只复用严格文本边界；临时摘要不能替换原文件指纹或首版登记。
        KnowledgeTextImportService.sourceIdentity(request.body());
        String operation = "POST /api/v1/knowledge/text-import-batches/" + id + "/entries/" + entryId + "/publish-links";
        return commands.execute(owner, key, operation, request, KnowledgeDtos.ItemDetail.class, () -> {
            Detail batch = snapshot(owner, find(owner, id));
            Entry entry = batch.entries().stream().filter(value -> value.id().equals(entryId))
                    .findFirst().orElseThrow(() -> new ApiException(404, "TEXT_IMPORT_BATCH_ENTRY_NOT_FOUND",
                            "该文件项不存在或不属于此批次。"));
            if (!"IMPORTED".equals(entry.state())) {
                throw new ApiException(409, "TEXT_IMPORT_ENTRY_NOT_IMPORTED", "请先完成这一项导入，再补齐正文链接。");
            }
            var current = knowledge.detail(owner, entry.record().itemId());
            if (request.body().equals(current.published().body())) throw invalid("正文没有变化，无需发布新版本。");
            // 追加知识版本不消费清单决策：首版登记、批次修订及源身份全部保持。
            return knowledge.publishWithinCommand(owner, current.id(), request.expectedVersionId(),
                    request.expectedDraftRevision(), current.published().title(), request.body());
        });
    }

    private BatchRow find(String owner, String id) {
        BatchRow batch = mapper.find(owner, id);
        if (batch == null) throw new ApiException(404, "TEXT_IMPORT_BATCH_NOT_FOUND", "导入批次不存在或无权访问。");
        return batch;
    }

    private EntryRow pending(String owner, BatchRow batch, String entryId, long revision) {
        if (batch.revision() != revision) {
            throw new ApiException(409, "BATCH_REVISION_CONFLICT",
                    "批次已由其他请求更新，请重新读取进度后再决定。", batch.revision());
        }
        EntryRow entry = mapper.entries(owner, batch.id()).stream().filter(row -> row.id().equals(entryId))
                .findFirst().orElseThrow(() -> new ApiException(404, "TEXT_IMPORT_BATCH_ENTRY_NOT_FOUND",
                        "该文件项不存在或不属于此批次。"));
        if (!"PENDING".equals(entry.state())) {
            throw new ApiException(409, "BATCH_ENTRY_RESOLVED", "这一项已完成导入或已明确跳过，请重新读取批次进度。");
        }
        return entry;
    }

    private void resolve(String owner, BatchRow batch, String entryId, String state, String recordId) {
        if (mapper.resolve(owner, batch.id(), entryId, state, recordId) != 1
                || mapper.advance(owner, batch.id(), batch.revision()) != 1) {
            throw new IllegalStateException("导入批次状态无法原子推进");
        }
    }

    private Detail snapshot(String owner, BatchRow batch) {
        return snapshot(owner, batch, mapper.entries(owner, batch.id()));
    }

    private DirectoryDetail directorySnapshot(String owner, BatchRow batch) {
        var rows = mapper.entries(owner, batch.id());
        Detail detail = snapshot(owner, batch, rows);
        var paths = new ArrayList<EntryPath>(rows.size());
        var unique = new HashSet<String>();
        for (EntryRow row : rows) {
            if (row.sourcePath() == null || !unique.add(row.sourcePath())) {
                throw new IllegalStateException("导入批次相对路径清单不完整或重复");
            }
            try {
                // 平面批次保留原文件名规则；旧资料只能回填名称，不能推断其历史目录。
                if (row.sourcePath().equals(row.fileName())) KnowledgeTextImportService.fileName(row.sourcePath());
                else sourcePath(row.sourcePath(), row.fileName());
            } catch (ApiException exception) {
                throw new IllegalStateException("导入批次相对路径与文件名不一致", exception);
            }
            paths.add(new EntryPath(row.id(), row.sourcePath()));
        }
        return new DirectoryDetail(detail, List.copyOf(paths));
    }

    private static void sourcePath(String value, String fileName) {
        if (value == null || value.isEmpty() || value.length() > 600
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.matches("(?s).*[\\x00-\\x1f\\x7f\\\\:].*")) {
            throw invalid("相对路径须为最多600字符的有效文本，不能包含控制字符、反斜杠或冒号。");
        }
        String[] segments = value.split("/", -1);
        if (segments.length > 32 || !segments[segments.length - 1].equals(fileName)) {
            throw invalid("相对路径最多32段，最后一段须与文件名完全一致。");
        }
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw invalid("相对路径不能包含空段、当前目录或上级目录。");
            }
        }
    }

    private Detail snapshot(String owner, BatchRow batch, List<EntryRow> rows) {
        if (rows.size() != batch.total() || batch.total() < 1 || batch.total() > 20) {
            throw new IllegalStateException("导入批次文件清单不完整");
        }
        List<Entry> entries = new ArrayList<>(rows.size());
        long resolved = 0;
        for (int index = 0; index < rows.size(); index++) {
            EntryRow row = rows.get(index);
            if (row.ordinal() != index + 1 || !List.of("PENDING", "IMPORTED", "SKIPPED").contains(row.state())
                    || "IMPORTED".equals(row.state()) != (row.importRecordId() != null)) {
                throw new IllegalStateException("导入批次文件状态或顺序异常");
            }
            KnowledgeTextImportDtos.Entry record = row.importRecordId() == null ? null : imports.find(owner, row.importRecordId());
            if (row.importRecordId() != null && (record == null || !record.fileName().equals(row.fileName())
                    || !record.sourceSha256().equals(row.sourceSha256()) || record.sourceBytes() != row.sourceBytes()
                    || record.sourceCharacters() != row.sourceCharacters())) {
                throw new IllegalStateException("导入批次首版登记与原文件身份不一致");
            }
            if (!"PENDING".equals(row.state())) resolved++;
            entries.add(new Entry(row.id(), row.ordinal(), row.fileName(), row.sourceSha256(), row.sourceBytes(),
                    row.sourceCharacters(), row.state(), row.hasRegisteredSource(), record));
        }
        if (resolved != batch.revision()) throw new IllegalStateException("导入批次修订号与已处理项不一致");
        return new Detail(batch.id(), batch.revision(), resolved == batch.total() ? "COMPLETE" : "OPEN",
                batch.createdAt(), List.copyOf(entries));
    }
}
