package com.wenjie.studio.search;

import com.wenjie.studio.security.OwnerProperties;
import com.wenjie.studio.dataprivacy.PrivacyLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

@Component
public final class SearchRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(SearchRuntime.class);
    private static final int BATCH = 25;
    private final SearchProperties properties;
    private final SearchStore store;
    private final String owner;
    private final GenerationIndex index;
    private final PrivacyLifecycle privacy;
    private volatile String localError;
    private volatile String verifiedGeneration;

    public SearchRuntime(SearchProperties properties, SearchStore store, OwnerProperties owner, PrivacyLifecycle privacy) {
        this.properties = properties;
        this.store = store;
        this.owner = owner.ownerId();
        this.privacy = privacy;
        this.index = new GenerationIndex(properties, owner.ownerId());
    }

    @Scheduled(fixedDelayString = "${studio.search.poll-delay-ms:3000}", initialDelay = 1000)
    public synchronized void tick() {
        if (!properties.enabled()) return;
        try {
            // 清除意图已持久化而业务事务尚未对齐时，不得从旧正文重建索引副本。
            privacy.requireSynchronized(owner);
            SearchRows.Projection state = store.projection(owner);
            if (state == null) state = queueIfNeeded();
            if (localError != null && state.buildGeneration() == null) state = queueIfNeeded();
            if (state.buildGeneration() == null) {
                try {
                    index.verify(state.activeGeneration());
                    verifiedGeneration = state.activeGeneration();
                } catch (IOException | IllegalArgumentException exception) {
                    state = queueIfNeeded();
                }
            }
            if (state.buildGeneration() != null) build(state);
            else consume(state);
            localError = null;
            store.clearError(owner);
        } catch (Exception exception) {
            localError = "SEARCH_INDEX_UNAVAILABLE";
            // 异常可能带文件路径或索引内容，只记录分类；业务保存不依赖这个后台结果。
            LOG.warn("event=search_projection_failed exceptionType={}", exception.getClass().getSimpleName());
            try { store.error(owner, localError); }
            catch (Exception ignored) { LOG.warn("event=search_error_state_unavailable"); }
        }
    }

    public synchronized SearchRows.Window search(String generation, SearchDtos.Criteria criteria, int candidateLimit) {
        if (!properties.enabled() || generation == null) return new SearchRows.Window(List.of(), false);
        try {
            SearchRows.Window result = index.search(generation, criteria, candidateLimit);
            verifiedGeneration = generation;
            return result;
        } catch (IOException | RuntimeException exception) {
            localError = "SEARCH_INDEX_UNAVAILABLE";
            LOG.warn("event=search_query_projection_failed exceptionType={}", exception.getClass().getSimpleName());
            return new SearchRows.Window(List.of(), false);
        }
    }

    public String error() { return localError; }
    public boolean verified(String generation) { return generation != null && generation.equals(verifiedGeneration); }

    private SearchRows.Projection queueIfNeeded() {
        return store.locked(owner, () -> {
            SearchRows.Projection state = store.projection(owner);
            if (state == null || state.buildGeneration() == null) store.queue(owner);
            return store.projection(owner);
        });
    }

    private void build(SearchRows.Projection state) throws IOException {
        try {
            index.verify(state.buildGeneration());
        } catch (IOException | IllegalArgumentException exception) {
            try {
                if ("SCANNING".equals(state.phase()) && state.scanCursor() == null) {
                    index.initialize(state.buildGeneration());
                } else {
                    throw new IOException("BUILD_CHECKPOINT_MISSING");
                }
            } catch (IOException invalid) {
                // 中途丢失或损坏的目录不能沿用扫描游标，否则早期内容会永久缺席。
                state = store.locked(owner, () -> {
                    store.queue(owner);
                    return store.projection(owner);
                });
                index.initialize(state.buildGeneration());
            }
        }
        if ("SCANNING".equals(state.phase())) {
            List<String> ids = store.scan(owner, state.scanCursor(), BATCH);
            index.update(state.buildGeneration(), ids, store.documents(owner, ids));
            store.scanned(owner, state.buildGeneration(), ids.isEmpty() ? state.scanCursor() : ids.getLast(), ids.size() < BATCH);
            return;
        }

        List<SearchRows.Event> events = store.buildEvents(state, BATCH);
        apply(state.buildGeneration(), events);
        store.applied(owner, state.buildGeneration(), events);
        String generation = state.buildGeneration();
        boolean activated = store.locked(owner, () -> {
            SearchRows.Projection current = store.projection(owner);
            if (!Objects.equals(generation, current.buildGeneration())) return false;
            List<SearchRows.Event> tail = store.buildEvents(current, BATCH + 1);
            if (tail.size() > BATCH) return false;
            try {
                // 锁内只追赶有限尾部；持久提交后才原子切换数据库中的唯一代际指针。
                apply(generation, tail);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            store.applied(owner, generation, tail);
            store.activate(owner, generation);
            return true;
        });
        if (activated) verifiedGeneration = generation;
    }

    private void consume(SearchRows.Projection state) throws IOException {
        List<SearchRows.Event> events = store.pendingEvents(owner, BATCH);
        if (events.isEmpty()) return;
        apply(state.activeGeneration(), events);
        // 仅确认本批实际持久化的 ID；较小但稍晚提交的事件仍留在未处理集合。
        store.acknowledge(owner, events);
    }

    private void apply(String generation, List<SearchRows.Event> events) throws IOException {
        if (events.isEmpty()) return;
        List<String> ids = events.stream().map(SearchRows.Event::itemId).distinct().toList();
        index.update(generation, ids, store.documents(owner, ids));
    }
}
