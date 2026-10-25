package com.botwithus.msgpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A MessagePack map, kept as its entries in wire order. Keys may be of any
 * type, and a decoded map keeps duplicate keys exactly as they arrived, so it
 * packs back to the same bytes. Equality is entry by entry, in order.
 *
 * @param entries the entries in wire order, as an unmodifiable copy
 */
public record MsgMap(List<Entry> entries) implements MsgValue {

    /**
     * @param key   the key
     * @param value the value
     */
    public record Entry(MsgValue key, MsgValue value) {

        /** Rejects a null key or value. */
        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    /** Copies {@code entries}, so the map stays immutable. */
    public MsgMap {
        entries = List.copyOf(entries);
    }

    /** @return the empty map */
    public static MsgMap empty() {
        return new MsgMap(List.of());
    }

    /** @return a builder for a map with unique keys, in insertion order */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public MsgType type() {
        return MsgType.MAP;
    }

    /** @return the number of entries */
    public int size() {
        return entries.size();
    }

    /** @return whether the map has no entries */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * @return the value of the first entry whose key is the string {@code key}.
     *         A key that is not valid UTF-8 never matches, and is never decoded
     */
    public Optional<MsgValue> get(String key) {
        return get(MsgString.of(key));
    }

    /** @return the value of the first entry whose key equals {@code key} */
    public Optional<MsgValue> get(MsgValue key) {
        for (Entry entry : entries) {
            if (entry.key().equals(key)) {
                return Optional.of(entry.value());
            }
        }
        return Optional.empty();
    }

    /**
     * Builds a map with unique keys. Putting a key a second time replaces its
     * value and keeps its original position.
     */
    public static final class Builder {

        private final Map<MsgValue, MsgValue> entries = new LinkedHashMap<>();

        private Builder() {
        }

        /** @return this builder, with {@code key} (as a string) mapped to {@code value} */
        public Builder put(String key, MsgValue value) {
            return put(MsgString.of(key), value);
        }

        /** @return this builder, with {@code key} mapped to {@code value} */
        public Builder put(MsgValue key, MsgValue value) {
            entries.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** @return the map built so far; the builder stays usable */
        public MsgMap build() {
            List<Entry> list = new ArrayList<>(entries.size());
            entries.forEach((k, v) -> list.add(new Entry(k, v)));
            return new MsgMap(list);
        }
    }
}
