package com.wenjie.studio.search;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 封闭恢复专用：使用生产分析器与文档格式生成新代，不接触旧索引或启动调度器。 */
public final class SearchArchiveRecovery {
    private static final int BATCH = 100;
    private static final long MAX_DOCUMENTS = 2_000_000;
    private SearchArchiveRecovery() { }

    public static void rebuild(Connection target, SearchMapper mapper, String owner,
                               Path newRoot, JsonMapper json) throws Exception {
        safeDirectory(newRoot);
        try (var children = Files.newDirectoryStream(newRoot)) {
            if (children.iterator().hasNext()) throw new IOException("RESTORE_INDEX_ROOT_NOT_EMPTY");
        }
        String generation = UUID.randomUUID().toString();
        var index = new GenerationIndex(new SearchProperties(true, newRoot.toString(), 3000), owner);
        index.initialize(generation);
        mapper.queueBuild(owner, generation, mapper.maximumEvent(owner));
        String cursor = null;
        long scanned = 0;
        for (;;) {
            List<String> ids = mapper.scanIds(owner, cursor, BATCH);
            if (ids.isEmpty()) break;
            scanned += ids.size();
            if (scanned > MAX_DOCUMENTS) throw new IOException("RESTORE_INDEX_CAPACITY");
            List<String> available = available(target, owner, ids);
            index.update(generation, ids, documents(mapper, owner, available, json));
            cursor = ids.getLast();
        }
        mapper.activate(owner, generation);
        verify(target, mapper, owner, newRoot);
        // 只有新代逐项核验后才确认恢复快照中的确切 outbox 事件。
        for (;;) {
            var events = mapper.pendingEvents(owner, BATCH);
            if (events.isEmpty()) break;
            mapper.acknowledge(owner, events.stream().map(SearchRows.Event::id).toList());
        }
    }

    public static void verify(Connection target, SearchMapper mapper, String owner, Path root) throws Exception {
        safeDirectory(root);
        var projection = mapper.projection(owner);
        if (projection == null || projection.activeGeneration() == null || projection.buildGeneration() != null
                || !"IDLE".equals(projection.phase()) || projection.lastErrorCode() != null)
            throw new IOException("RESTORE_INDEX_PROJECTION_NOT_READY");
        String generation = projection.activeGeneration();
        if (!UUID.fromString(generation).toString().equals(generation)) throw new IOException("RESTORE_INDEX_GENERATION_INVALID");
        Path directoryPath = root.resolve(generation);
        safeDirectory(directoryPath);
        new GenerationIndex(new SearchProperties(true, root.toString(), 3000), owner).verify(generation);
        try (var directory = FSDirectory.open(directoryPath); var reader = DirectoryReader.open(directory)) {
            var searcher = new IndexSearcher(reader);
            long expected = 0;
            String cursor = null;
            for (;;) {
                List<String> ids = mapper.scanIds(owner, cursor, BATCH);
                if (ids.isEmpty()) break;
                var available = available(target, owner, ids);
                var contents = mapper.contents(owner, ids);
                Map<String, SearchRows.Content> byId = new HashMap<>();
                contents.forEach(content -> byId.put(content.id(), content));
                for (String id : ids) {
                    var query = new BooleanQuery.Builder()
                            .add(new TermQuery(new Term("id", id)), BooleanClause.Occur.FILTER)
                            .add(new TermQuery(new Term("owner", owner)), BooleanClause.Occur.FILTER).build();
                    int matches = searcher.count(query);
                    if (!available.contains(id)) {
                        if (matches != 0) throw new IOException("RESTORE_INDEX_CONTAINS_UNAVAILABLE_CONTENT");
                        continue;
                    }
                    var content = byId.get(id);
                    if (content == null || matches != 1) throw new IOException("RESTORE_INDEX_DOCUMENT_MISSING");
                    var hit = searcher.search(query, 1).scoreDocs[0];
                    var stored = reader.storedFields().document(hit.doc);
                    if (!content.versionId().equals(stored.get("version_id"))
                            || content.metadataRevision() != stored.getField("metadata_revision").numericValue().longValue()
                            || content.organizationRevision() != stored.getField("organization_revision").numericValue().longValue())
                        throw new IOException("RESTORE_INDEX_DOCUMENT_STALE");
                    if (++expected > MAX_DOCUMENTS) throw new IOException("RESTORE_INDEX_CAPACITY");
                }
                cursor = ids.getLast();
            }
            if (expected != reader.numDocs()) throw new IOException("RESTORE_INDEX_DOCUMENT_COUNT_MISMATCH");
        }
    }

    private static List<SearchRows.DocumentData> documents(SearchMapper mapper, String owner,
                                                          List<String> ids, JsonMapper json) {
        if (ids.isEmpty()) return List.of();
        Map<String, List<String>> topics = new HashMap<>();
        for (var link : mapper.topicLinks(owner, ids))
            topics.computeIfAbsent(link.itemId(), ignored -> new ArrayList<>()).add(link.topicId());
        return mapper.contents(owner, ids).stream().map(content -> new SearchRows.DocumentData(content,
                List.of(json.readValue(content.aliasesJson(), String[].class)),
                List.of(json.readValue(content.tagsJson(), String[].class)),
                List.copyOf(topics.getOrDefault(content.id(), List.of())))).toList();
    }

    private static List<String> available(Connection target, String owner, List<String> ids) throws SQLException {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        try (var query = target.prepareStatement("SELECT i.id FROM knowledge_item i WHERE i.owner_id=? AND i.id IN ("
                + placeholders + ") AND NOT EXISTS(SELECT 1 FROM privacy_object_state s WHERE s.owner_id=i.owner_id"
                + " AND s.object_kind='KNOWLEDGE' AND s.object_id=i.id AND s.state<>'AVAILABLE') ORDER BY i.id")) {
            query.setQueryTimeout(10);
            query.setString(1, owner);
            for (int index = 0; index < ids.size(); index++) query.setString(index + 2, ids.get(index));
            List<String> result = new ArrayList<>();
            try (var rows = query.executeQuery()) { while (rows.next()) result.add(rows.getString(1)); }
            return List.copyOf(result);
        }
    }

    private static void safeDirectory(Path path) throws IOException {
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize()))
            throw new IOException("RESTORE_INDEX_DIRECTORY_INVALID");
        Path cursor = path.getRoot();
        for (Path segment : path) {
            cursor = cursor.resolve(segment);
            if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(cursor))
                throw new IOException("RESTORE_INDEX_DIRECTORY_UNSAFE");
        }
        if (!path.toRealPath().equals(path)) throw new IOException("RESTORE_INDEX_DIRECTORY_UNSAFE");
    }
}
