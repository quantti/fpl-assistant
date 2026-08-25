package dev.quantti.fplassistant.myteam;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A few dozen entries with a fixed lifetime. Deliberately not a cache library: four caches with
 * one TTL each do not earn a dependency, {@code @EnableCaching}, and a cache configuration this
 * project has so far done without.
 *
 * <p>Entries are keyed by FPL entry id or league id, never by user id — a user who edits their
 * stored team id must not be served the previous team's squad, and standings are the same
 * standings whoever asks for them.
 */
public final class TtlCache<K, V> {

    private record Entry<V>(V value, Instant expiresAt) {
    }

    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxEntries;

    public TtlCache(Duration ttl, int maxEntries) {
        this.ttl = ttl;
        this.maxEntries = maxEntries;
    }

    public Optional<V> get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    public void put(K key, V value) {
        if (entries.size() >= maxEntries) {
            // Expired first; if the cache is genuinely full of live entries, start over rather
            // than track access order. Losing a five-minute cache costs one extra FPL call.
            entries.values().removeIf(e -> e.expiresAt().isBefore(Instant.now()));
            if (entries.size() >= maxEntries) {
                entries.clear();
            }
        }
        entries.put(key, new Entry<>(value, Instant.now().plus(ttl)));
    }
}
