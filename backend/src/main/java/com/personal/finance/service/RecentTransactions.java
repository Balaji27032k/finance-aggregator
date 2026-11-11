package com.personal.finance.service;

import com.personal.finance.dto.TransactionView;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Admin screens ask for the same wide window again and again (every keystroke in a search, every period change),
 * and each ask reads thousands of rows over a remote database. A short-lived copy keeps them quick; new data shows
 * up within {@link #TTL_NANOS}.
 */
@Service
public class RecentTransactions {

    static final long TTL_NANOS = 30_000_000_000L;
    private static final int MAX_ENTRIES = 16;

    private record Key(Instant from, Instant to, int limit) {
    }

    private record Cached(long at, List<TransactionView> value) {
    }

    private final TransactionQueryService source;
    private final Map<Key, Cached> cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
            return size() > MAX_ENTRIES;
        }
    });

    public RecentTransactions(TransactionQueryService source) {
        this.source = source;
    }

    public List<TransactionView> list(Instant from, Instant to, int limit) {
        Key key = new Key(from, to, limit);
        long now = System.nanoTime();
        Cached hit = cache.get(key);
        if (hit != null && now - hit.at() < TTL_NANOS) {
            return hit.value();
        }
        List<TransactionView> fresh = List.copyOf(source.list(from, to, limit));
        cache.put(key, new Cached(now, fresh));
        return fresh;
    }
}
