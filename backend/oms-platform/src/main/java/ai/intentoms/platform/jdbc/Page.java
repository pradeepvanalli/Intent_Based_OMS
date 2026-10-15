package ai.intentoms.platform.jdbc;

import java.util.List;

/** Keyset/offset page wrapper used by list endpoints. */
public record Page<T>(List<T> items, long total, int offset, int limit) {
    public static <T> Page<T> of(List<T> items, long total, int offset, int limit) { return new Page<>(items, total, offset, limit); }
}
