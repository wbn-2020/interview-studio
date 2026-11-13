package com.wenjie.studio.dataprivacy;

import com.wenjie.studio.ai.AiDraftBuffer;
import com.wenjie.studio.ai.AiRuntime;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.dataarchive.DataArchiveProperties;
import com.wenjie.studio.dataarchive.DataArchiveRuntime;
import com.wenjie.studio.material.MaterialRuntime;
import com.wenjie.studio.material.MaterialStorage;
import com.wenjie.studio.search.SearchProperties;
import com.wenjie.studio.search.SearchRuntime;
import com.wenjie.studio.search.SearchStore;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** 只处理业务拥有且在确认清单中的副本；备份、审计日志与用户下载副本不在此范围。 */
@Component
public final class PrivacyStorageEffects {
    private final PrivacyEffectsMapper mapper;
    private final AiRuntime ai;
    private final ObjectProvider<MaterialRuntime> parser;
    private final SearchRuntime search;
    private final DataArchiveRuntime archive;
    private final AiDraftBuffer drafts;
    private final SearchProperties searchProperties;
    private final SearchStore searchStore;
    private final MaterialStorage materials;
    private final DataArchiveProperties archiveProperties;

    public PrivacyStorageEffects(PrivacyEffectsMapper mapper, AiRuntime ai, ObjectProvider<MaterialRuntime> parser,
                                 SearchRuntime search, DataArchiveRuntime archive, AiDraftBuffer drafts,
                                 SearchProperties searchProperties, SearchStore searchStore,
                                 MaterialStorage materials, DataArchiveProperties archiveProperties) {
        this.mapper = mapper; this.ai = ai; this.parser = parser; this.search = search; this.archive = archive;
        this.drafts = drafts; this.searchProperties = searchProperties; this.searchStore = searchStore;
        this.materials = materials; this.archiveProperties = archiveProperties;
    }

    public <T> T withoutWorkers(Supplier<T> work) {
        // 必须先取得 worker 锁，再进入 owner 事务；反向顺序会与 worker 的提交锁互相等待。
        synchronized (ai) {
            Object parserLock = parser.getIfAvailable();
            // 附件解析未启用时没有 worker bean，仍保留同一锁序而不阻止应用启动。
            synchronized (parserLock == null ? this : parserLock) {
                synchronized (archive) {
                    synchronized (search) { return work.get(); }
                }
            }
        }
    }

    public void requireQuiescent(String owner) {
        if (mapper.activeWork(owner) != 0) {
            throw new ApiException(409, "PRIVACY_BACKGROUND_WORK_ACTIVE",
                    "仍有 AI、传输、解析或完整导出任务排队／执行中。请先等待或取消后重新预览，避免在清除时生成新副本。");
        }
    }

    public void requireUnsharedMaterials(String owner, List<PrivacyLedger.Subject> subjects) {
        for (var subject : subjects) {
            if ("MATERIAL".equals(subject.kind()) && (mapper.sharedAttachmentNotes(owner, subject.id()) != 0
                    || mapper.sharedAttachmentBoards(owner, subject.id()) != 0)) {
                throw new ApiException(409, "PRIVACY_ATTACHMENT_STILL_SHARED",
                        "原附件仍被有效笔记或白板引用。请先明确移除这些引用，不能因清除一个来源而销毁共享文件。");
            }
        }
    }

    public List<String> exportIds(String owner) { return mapper.exports(owner); }

    public void purge(String owner, List<PrivacyLedger.Subject> subjects) {
        requireQuiescent(owner);
        requireUnsharedMaterials(owner, subjects);
        try {
            List<String> knowledge = subjects.stream().filter(subject -> "KNOWLEDGE".equals(subject.kind()))
                    .map(PrivacyLedger.Subject::id).toList();
            if (!knowledge.isEmpty()) {
                purgeIndexGenerations(searchProperties.directory(), owner, knowledge);
                if (searchProperties.enabled()) searchStore.queue(owner);
            }
            for (var subject : subjects) {
                if (!"MATERIAL".equals(subject.kind())) continue;
                for (String upload : mapper.uploads(owner, subject.id())) {
                    Path root = materials.root();
                    clearIfExists(root, root.resolve(owner).resolve(subject.id()).resolve("uploads").resolve(upload + ".bin"));
                }
                for (String run : mapper.parseRuns(owner, subject.id())) {
                    Path root = materials.root();
                    Path directory = root.resolve(owner).resolve(subject.id()).resolve("runs").resolve(run);
                    clearIfExists(root, directory.resolve("result.json"));
                    clearIfExists(root, directory.resolve("preview.png"));
                }
            }
            // 完整包混合全库正文，不能在 ZIP 内猜测可分离片段。所有已登记旧包明确整体失效并清空。
            List<String> exports = mapper.exports(owner);
            if (!exports.isEmpty()) {
                Path root = safeRoot(archiveProperties.directory());
                for (String export : exports) clearIfExists(root, root.resolve(owner).resolve(export + ".zip"));
            }
            drafts.close();
        } catch (IOException exception) {
            throw new ApiException(503, "PRIVACY_COPY_PURGE_INCOMPLETE",
                    "部分索引或业务副本尚未清除，独立账本已保留清除意图。相关数据保持封闭，请接续原操作。");
        }
    }

    static void purgeIndexGenerations(String configured, String owner, List<String> ids) throws IOException {
        if (configured == null || configured.isBlank()) return;
        Path root = Path.of(configured);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        root = safeRoot(configured);
        List<Path> generations;
        try (var children = Files.list(root)) {
            generations = children.filter(path -> path.getFileName().toString()
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                    .limit(10_001).toList();
        }
        if (generations.size() > 10_000) throw new IOException("PRIVACY_INDEX_SCOPE_CAPACITY");
        for (Path generation : generations) {
            safeRoot(generation.toString());
            try (var directory = FSDirectory.open(generation)) {
                if (!DirectoryReader.indexExists(directory)) continue;
                java.util.Map<String, String> commitData;
                try (var reader = DirectoryReader.open(directory)) {
                    commitData = reader.getIndexCommit().getUserData();
                    if (!owner.equals(commitData.get("owner")) || !"knowledge-v1".equals(commitData.get("format"))
                            || !generation.getFileName().toString().equals(commitData.get("generation"))) {
                        throw new IOException("PRIVACY_INDEX_OWNER_MISMATCH");
                    }
                }
                try (var analyzer = new StandardAnalyzer();
                     var writer = new IndexWriter(directory, new IndexWriterConfig(analyzer)
                             .setOpenMode(IndexWriterConfig.OpenMode.APPEND).setCommitOnClose(false))) {
                    for (String id : ids) writer.deleteDocuments(new Term("id", id));
                    writer.setLiveCommitData(commitData.entrySet());
                    writer.forceMergeDeletes(true);
                    writer.commit();
                    writer.deleteUnusedFiles();
                }
                try (var reader = DirectoryReader.open(directory)) {
                    var searcher = new IndexSearcher(reader);
                    for (String id : ids) if (searcher.count(new TermQuery(new Term("id", id))) != 0) {
                        throw new IOException("PRIVACY_INDEX_READBACK_FAILED");
                    }
                }
            }
        }
    }

    static Path safeRoot(String configured) throws IOException {
        if (configured == null || configured.isBlank()) throw new IOException("PRIVACY_STORAGE_UNSET");
        Path root = Path.of(configured);
        if (!root.isAbsolute() || !root.normalize().equals(root)) throw new IOException("PRIVACY_STORAGE_RELATIVE");
        Path current = root.getRoot();
        if (current == null) throw new IOException("PRIVACY_STORAGE_RELATIVE");
        for (Path segment : root) {
            current = current.resolve(segment);
            var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("PRIVACY_STORAGE_REDIRECTED");
            }
        }
        if (!root.toRealPath().equals(root)) throw new IOException("PRIVACY_STORAGE_REDIRECTED");
        return root;
    }

    static void clearIfExists(Path root, Path file) throws IOException {
        root = safeRoot(root.toString());
        file = file.toAbsolutePath().normalize();
        if (!file.startsWith(root) || file.equals(root)) throw new IOException("PRIVACY_FILE_OUTSIDE_ROOT");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        safeRoot(file.getParent().toString());
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("PRIVACY_FILE_UNSAFE");
        }
        // 逻辑清空业务副本，不承诺覆盖存储介质残留；不删除保留记录、目录或备份。
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.truncate(0);
            channel.force(true);
            if (channel.size() != 0) throw new IOException("PRIVACY_FILE_READBACK_FAILED");
        }
    }
}
