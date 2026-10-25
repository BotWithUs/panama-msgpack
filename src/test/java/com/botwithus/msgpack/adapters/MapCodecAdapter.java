package com.botwithus.msgpack.adapters;

import com.botwithus.msgpack.MsgExtensionHeader;
import com.botwithus.msgpack.MsgPackException;
import com.botwithus.msgpack.MsgPacker;
import com.botwithus.msgpack.MsgTimestamps;
import com.botwithus.msgpack.MsgType;
import com.botwithus.msgpack.MsgUnpacker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The first consumer's {@code Map <-> byte[]} codec, rebuilt on this library's
 * public API alone. It lives in a separate package so it can see nothing more
 * than a consumer can. The consumer-specific behaviour lives here, not in the
 * library:
 * <ul>
 *   <li>the asymmetric depth count, where a nested map costs two levels and an
 *       array element one;</li>
 *   <li>every integer decoded as a {@code Long}, and a uint64 above {@code Long.MAX_VALUE}
 *       rejected. The legacy code reads as if it picks {@code Integer} for the int range, but
 *       its {@code isInIntRange() ? asInt() : asLong()} ternary widens the int arm to
 *       {@code long} before boxing, so it has only ever produced {@code Long};</li>
 *   <li>an extension turned into a string: a timestamp as {@code Instant.toString()},
 *       anything else in msgpack-java's {@code (type,0x...)} spelling;</li>
 *   <li>a map root required, string keys required, and trailing bytes ignored.</li>
 * </ul>
 * The differential suite holds it to the legacy codec, byte for byte and value
 * for value.
 */
public final class MapCodecAdapter {

    /** Stands in for the consumer's own codec exception. */
    public static final class AdapterException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        AdapterException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final int MAX_DEPTH = 64;
    private static final int HEX_RADIX = 16;

    private MapCodecAdapter() {
    }

    /** @return {@code map} packed exactly as the legacy codec packs it */
    public static byte[] encode(Map<String, Object> map) {
        MsgPacker packer = new MsgPacker();
        packMap(packer, map);
        return packer.toByteArray();
    }

    /**
     * @return the map {@code data} starts with
     * @throws AdapterException for anything the legacy codec refused
     */
    public static Map<String, Object> decode(byte[] data) {
        try {
            MsgUnpacker unpacker = new MsgUnpacker(data);
            if (unpacker.nextType() != MsgType.MAP) {
                throw new MsgPackException("the root value is a " + unpacker.nextType() + ", not a map");
            }
            return readMapBody(unpacker, unpacker.unpackMapHeader(), 0);
        } catch (MsgPackException e) {
            throw new AdapterException(e.getMessage(), e);
        } catch (RuntimeException e) {
            // The legacy codec wrapped everything, null input included.
            throw new AdapterException("MessagePack decode failed", e);
        }
    }

    private static void packMap(MsgPacker packer, Map<?, ?> map) {
        packer.packMapHeader(map.size());
        for (var entry : map.entrySet()) {
            packer.packString(requireStringKey(entry.getKey()));
            packValue(packer, entry.getValue());
        }
    }

    /** The legacy codec failed a non-string key with these two exception types; keep them. */
    private static String requireStringKey(Object key) {
        return switch (key) {
            case String s -> s;
            case null -> throw new NullPointerException("a map key is null");
            default -> throw new ClassCastException("a map key is a " + key.getClass().getName()
                    + ", not a String");
        };
    }

    private static void packValue(MsgPacker packer, Object value) {
        switch (value) {
            case null -> packer.packNil();
            case String s -> packer.packString(s);
            case Integer i -> packer.packInt(i);
            case Long l -> packer.packLong(l);
            case Float f -> packer.packFloat(f);
            case Double d -> packer.packDouble(d);
            case Boolean b -> packer.packBoolean(b);
            case Map<?, ?> m -> packMap(packer, m);
            case List<?> list -> {
                packer.packArrayHeader(list.size());
                for (Object item : list) {
                    packValue(packer, item);
                }
            }
            default -> packer.packString(value.toString());
        }
    }

    private static Map<String, Object> readMapBody(MsgUnpacker unpacker, int count, int depth) {
        requireDepth(depth);
        Map<String, Object> result = LinkedHashMap.newLinkedHashMap(count);
        for (int i = 0; i < count; i++) {
            if (unpacker.nextType() != MsgType.STRING) {
                throw new MsgPackException("a map key is a " + unpacker.nextType() + ", not a string");
            }
            String key = unpacker.unpackString();
            result.put(key, readValue(unpacker, depth + 1));
        }
        return result;
    }

    private static Object readValue(MsgUnpacker unpacker, int depth) {
        requireDepth(depth);
        return switch (unpacker.nextType()) {
            case NIL -> {
                unpacker.unpackNil();
                yield null;
            }
            case BOOLEAN -> unpacker.unpackBoolean();
            case INTEGER -> unpacker.unpackLong();
            case FLOAT -> unpacker.unpackDouble();
            case STRING -> unpacker.unpackString();
            case BINARY -> unpacker.unpackBinary();
            case EXTENSION -> extensionToString(unpacker);
            case ARRAY -> readArray(unpacker, depth);
            case MAP -> readMapBody(unpacker, unpacker.unpackMapHeader(), depth + 1);
        };
    }

    private static List<Object> readArray(MsgUnpacker unpacker, int depth) {
        int count = unpacker.unpackArrayHeader();
        List<Object> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(readValue(unpacker, depth + 1));
        }
        return list;
    }

    private static String extensionToString(MsgUnpacker unpacker) {
        MsgExtensionHeader header = unpacker.unpackExtensionHeader();
        byte[] payload = unpacker.readPayload(header.length());
        if (header.extType() == MsgTimestamps.TIMESTAMP_TYPE) {
            return MsgTimestamps.decodePayload(payload).toString();
        }
        StringBuilder text = new StringBuilder().append('(').append(header.extType()).append(",0x");
        for (byte b : payload) {
            text.append(Integer.toString(b, HEX_RADIX));
        }
        return text.append(')').toString();
    }

    private static void requireDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new MsgPackException("MessagePack nesting exceeds " + MAX_DEPTH + " levels");
        }
    }
}
