package com.wenjie.studio.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.QueryBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class GenerationIndex {
    private static final String FORMAT = "knowledge-v1";
    private final SearchProperties properties;
    private final String owner;

    GenerationIndex(SearchProperties properties, String owner) {
        this.properties = properties;
        this.owner = owner;
    }

    void initialize(String generation) throws IOException {
        Path path = path(generation);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("GENERATION_ALREADY_EXISTS");
        Files.createDirectories(path);
        try (Directory directory = FSDirectory.open(path); Analyzer analyzer = SearchAnalyzers.create();
             IndexWriter writer = new IndexWriter(directory, config(analyzer))) {
            writer.setLiveCommitData(commitData(generation).entrySet());
            writer.commit();
        }
    }

    void verify(String generation) throws IOException {
        Path path = path(generation);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("GENERATION_MISSING");
        try (Directory directory = FSDirectory.open(path); DirectoryReader reader = DirectoryReader.open(directory)) {
            verifyCommit(reader, generation);
        }
    }

    void update(String generation, List<String> ids, List<SearchRows.DocumentData> documents) throws IOException {
        verify(generation);
        Map<String, SearchRows.DocumentData> byId = documents.stream()
                .collect(java.util.stream.Collectors.toMap(data -> data.content().id(), data -> data));
        try (Directory directory = FSDirectory.open(path(generation)); Analyzer analyzer = SearchAnalyzers.create();
             IndexWriter writer = new IndexWriter(directory, config(analyzer))) {
            for (String id : ids) {
                SearchRows.DocumentData data = byId.get(id);
                if (data == null) writer.deleteDocuments(new Term("id", id));
                else writer.updateDocument(new Term("id", id), document(data));
            }
            writer.setLiveCommitData(commitData(generation).entrySet());
            // 只有这个持久提交完成，调用者才推进扫描游标或逐条确认 outbox。
            writer.commit();
        }
    }

    SearchRows.Window search(String generation, SearchDtos.Criteria criteria, int candidateLimit) throws IOException {
        try (Directory directory = FSDirectory.open(path(generation)); DirectoryReader reader = DirectoryReader.open(directory);
             Analyzer analyzer = SearchAnalyzers.create()) {
            verifyCommit(reader, generation);
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs result = searcher.search(query(criteria, analyzer), candidateLimit + 1);
            List<SearchRows.Candidate> candidates = new ArrayList<>();
            int end = Math.min(result.scoreDocs.length, candidateLimit);
            for (int i = 0; i < end; i++) {
                ScoreDoc score = result.scoreDocs[i];
                Document document = searcher.storedFields().document(score.doc);
                candidates.add(new SearchRows.Candidate(document.get("id"), document.get("version_id"),
                        document.getField("metadata_revision").numericValue().longValue(),
                        document.getField("organization_revision").numericValue().longValue()));
            }
            return new SearchRows.Window(List.copyOf(candidates), result.scoreDocs.length > end);
        }
    }

    private Query query(SearchDtos.Criteria criteria, Analyzer analyzer) {
        BooleanQuery.Builder result = new BooleanQuery.Builder();
        filter(result, "owner", owner);
        filter(result, "status", criteria.status());
        // 来源按当前真实登记回验；旧索引的 USER 字段不能屏蔽升级前已采用／导入的对象。
        if (criteria.kind() != null) filter(result, "kind", criteria.kind());
        if (criteria.topicId() != null) filter(result, "topic", criteria.topicId());
        if (criteria.tag() != null) filter(result, "tag_exact", criteria.tag().toLowerCase(Locale.ROOT));

        BooleanQuery.Builder words = new BooleanQuery.Builder();
        if (criteria.query().codePointCount(0, criteria.query().length()) == 1) {
            words.add(new TermQuery(new Term("character", criteria.query().toLowerCase(Locale.ROOT))), BooleanClause.Occur.SHOULD);
        } else {
            QueryBuilder builder = new QueryBuilder(analyzer);
            for (var entry : Map.of("title", 5f, "aliases", 4f, "tags", 3f,
                    "body", 1f, "code_exact", 4f, "code_parts", 2f).entrySet()) {
                Query query = builder.createBooleanQuery(entry.getKey(), criteria.query(), BooleanClause.Occur.MUST);
                if (query != null) words.add(new BoostQuery(query, entry.getValue()), BooleanClause.Occur.SHOULD);
            }
        }
        BooleanQuery text = words.build();
        result.add(text.clauses().isEmpty() ? new MatchNoDocsQuery() : text, BooleanClause.Occur.MUST);
        return result.build();
    }

    private static void filter(BooleanQuery.Builder query, String field, String value) {
        query.add(new TermQuery(new Term(field, value)), BooleanClause.Occur.FILTER);
    }

    private Document document(SearchRows.DocumentData data) {
        SearchRows.Content content = data.content();
        Document document = new Document();
        document.add(new StringField("id", content.id(), Field.Store.YES));
        document.add(new StringField("owner", content.ownerId(), Field.Store.NO));
        document.add(new StringField("kind", content.kind(), Field.Store.NO));
        document.add(new StringField("status", content.status(), Field.Store.NO));
        document.add(new StringField("source", content.source(), Field.Store.NO));
        document.add(new StoredField("version_id", content.versionId()));
        document.add(new StoredField("metadata_revision", content.metadataRevision()));
        document.add(new StoredField("organization_revision", content.organizationRevision()));
        document.add(new TextField("title", content.title(), Field.Store.NO));
        document.add(new TextField("body", content.body(), Field.Store.NO));
        document.add(new TextField("aliases", String.join(" ", data.aliases()), Field.Store.NO));
        document.add(new TextField("tags", String.join(" ", data.tags()), Field.Store.NO));
        for (String topic : data.topicIds()) document.add(new StringField("topic", topic, Field.Store.NO));
        for (String tag : data.tags()) document.add(new StringField("tag_exact", tag.toLowerCase(Locale.ROOT), Field.Store.NO));
        String allText = content.title() + "\n" + content.body() + "\n"
                + String.join(" ", data.aliases()) + "\n" + String.join(" ", data.tags());
        document.add(new TextField("code_exact", allText, Field.Store.NO));
        document.add(new TextField("code_parts", allText, Field.Store.NO));
        document.add(new TextField("character", allText, Field.Store.NO));
        return document;
    }

    private Path path(String generation) throws IOException {
        if (generation == null || !UUID.fromString(generation).toString().equals(generation)) throw new IOException("INVALID_GENERATION");
        if (properties.directory() == null || properties.directory().isBlank()) throw new IOException("SEARCH_DIRECTORY_REQUIRED");
        Path root = Path.of(properties.directory()).normalize();
        if (!root.isAbsolute() || Files.isSymbolicLink(root)) throw new IOException("INVALID_SEARCH_DIRECTORY");
        Path child = root.resolve(generation);
        if (!child.getParent().equals(root) || Files.isSymbolicLink(child)) throw new IOException("INVALID_GENERATION_DIRECTORY");
        return child;
    }

    private static IndexWriterConfig config(Analyzer analyzer) {
        return new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                .setRAMBufferSizeMB(16).setCommitOnClose(false);
    }

    private Map<String, String> commitData(String generation) {
        return Map.of("format", FORMAT, "owner", owner, "generation", generation);
    }

    private void verifyCommit(DirectoryReader reader, String generation) throws IOException {
        if (!reader.getIndexCommit().getUserData().equals(commitData(generation))) throw new IOException("GENERATION_COMMIT_MISMATCH");
    }
}
