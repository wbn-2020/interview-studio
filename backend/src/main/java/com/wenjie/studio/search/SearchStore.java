package com.wenjie.studio.search;

import com.wenjie.studio.common.IdempotencyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class SearchStore {
    private final SearchMapper mapper;
    private final IdempotencyMapper owners;
    private final JsonMapper json;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public SearchStore(SearchMapper mapper, IdempotencyMapper owners, JsonMapper json,
                       PlatformTransactionManager transactions) {
        this.mapper = mapper;
        this.owners = owners;
        this.json = json;
        write = new TransactionTemplate(transactions);
        write.setTimeout(30);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setTimeout(15);
    }

    public <T> T locked(String owner, Supplier<T> work) {
        return write.execute(status -> {
            // 第一条业务读取在写锁之后，避免使用锁等待前建立的旧快照做最终切换。
            owners.ensureWorkspace(owner);
            if (owners.lockWorkspace(owner) == null) throw new IllegalStateException("未能锁定检索工作区");
            return work.get();
        });
    }

    public SearchRows.Projection projection(String owner) { return mapper.projection(owner); }
    public long pending(String owner) { return mapper.pendingCount(owner); }

    public String queue(String owner) {
        String generation = UUID.randomUUID().toString();
        // 调用方已经持有 owner 锁；先前写事务都已提交，边界不会跨过同 owner 的在途事件。
        mapper.queueBuild(owner, generation, mapper.maximumEvent(owner));
        return generation;
    }

    public List<String> scan(String owner, String cursor, int limit) { return mapper.scanIds(owner, cursor, limit); }
    public List<SearchRows.Event> pendingEvents(String owner, int limit) { return mapper.pendingEvents(owner, limit); }
    public List<SearchRows.Event> buildEvents(SearchRows.Projection state, int limit) {
        return mapper.buildEvents(state.ownerId(), state.buildGeneration(), state.boundaryEventId(), limit);
    }

    public List<SearchRows.DocumentData> documents(String owner, List<String> ids) {
        if (ids.isEmpty()) return List.of();
        return read.execute(status -> {
            Map<String, List<String>> topics = new HashMap<>();
            for (SearchRows.TopicLink link : mapper.topicLinks(owner, ids)) {
                topics.computeIfAbsent(link.itemId(), ignored -> new ArrayList<>()).add(link.topicId());
            }
            return mapper.contents(owner, ids).stream().map(content -> new SearchRows.DocumentData(content,
                    strings(content.aliasesJson()), strings(content.tagsJson()),
                    List.copyOf(topics.getOrDefault(content.id(), List.of())))).toList();
        });
    }

    private List<String> strings(String value) {
        return List.of(json.readValue(value, String[].class));
    }

    public void scanned(String owner, String generation, String cursor, boolean finished) {
        write.executeWithoutResult(status -> mapper.advanceScan(owner, generation, cursor,
                finished ? "CATCHING_UP" : "SCANNING"));
    }

    public void acknowledge(String owner, List<SearchRows.Event> events) {
        if (events.isEmpty()) return;
        write.executeWithoutResult(status -> mapper.acknowledge(owner, events.stream().map(SearchRows.Event::id).toList()));
    }

    public void applied(String owner, String generation, List<SearchRows.Event> events) {
        if (events.isEmpty()) return;
        write.executeWithoutResult(status -> {
            for (SearchRows.Event event : events) mapper.applyBuildEvent(owner, generation, event.id());
        });
    }

    public void activate(String owner, String generation) { mapper.activate(owner, generation); }
    public void error(String owner, String code) {
        write.executeWithoutResult(status -> mapper.markError(owner, code));
    }
    public void clearError(String owner) { write.executeWithoutResult(status -> mapper.clearError(owner)); }
}
