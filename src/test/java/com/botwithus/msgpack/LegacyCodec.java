package com.botwithus.msgpack;

import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.IntegerValue;
import org.msgpack.value.MapValue;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The oracle: the msgpack-core based {@code Map <-> byte[]} codec the first
 * consumer of this library uses today, kept line for line, including the
 * asymmetric depth count and the stack-overflow conversion. Only the exception
 * type is local. A port to this library must behave exactly like this class.
 */
final class LegacyCodec {

    /** Stands in for the consumer's own codec exception. */
    static final class LegacyException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        LegacyException(String message) {
            super(message);
        }

        LegacyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final int MAX_DEPTH = 64;

    private LegacyCodec() {
    }

    static byte[] encode(Map<String, Object> map) {
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packMap(packer, map);
            return packer.toByteArray();
        } catch (IOException e) {
            throw new LegacyException("MessagePack encode failed", e);
        }
    }

    static Map<String, Object> decode(byte[] data) {
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(data)) {
            Value value = unpacker.unpackValue();
            return valueToMap(value.asMapValue(), 0);
        } catch (LegacyException e) {
            throw e;
        } catch (Exception e) {
            throw new LegacyException("MessagePack decode failed", e);
        } catch (StackOverflowError e) {
            throw new LegacyException("MessagePack decode failed: input too deeply nested", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void packMap(MessagePacker packer, Map<String, Object> map) throws IOException {
        packer.packMapHeader(map.size());
        for (var entry : map.entrySet()) {
            packer.packString(entry.getKey());
            packValue(packer, entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private static void packValue(MessagePacker packer, Object value) throws IOException {
        switch (value) {
            case null -> packer.packNil();
            case String s -> packer.packString(s);
            case Integer i -> packer.packInt(i);
            case Long l -> packer.packLong(l);
            case Float f -> packer.packFloat(f);
            case Double d -> packer.packDouble(d);
            case Boolean b -> packer.packBoolean(b);
            case Map<?, ?> m -> packMap(packer, (Map<String, Object>) m);
            case List<?> list -> {
                packer.packArrayHeader(list.size());
                for (Object item : list) {
                    packValue(packer, item);
                }
            }
            default -> packer.packString(value.toString());
        }
    }

    private static Map<String, Object> valueToMap(MapValue mapValue, int depth) {
        requireDepth(depth);
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : mapValue.entrySet()) {
            String key = entry.getKey().asStringValue().asString();
            result.put(key, valueToObject(entry.getValue(), depth + 1));
        }
        return result;
    }

    private static void requireDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new LegacyException("MessagePack nesting exceeds " + MAX_DEPTH + " levels");
        }
    }

    private static Object valueToObject(Value value, int depth) {
        requireDepth(depth);
        return switch (value.getValueType()) {
            case NIL -> null;
            case BOOLEAN -> value.asBooleanValue().getBoolean();
            case INTEGER -> {
                IntegerValue iv = value.asIntegerValue();
                yield iv.isInIntRange() ? iv.asInt() : iv.asLong();
            }
            case FLOAT -> value.asFloatValue().toDouble();
            case STRING -> value.asStringValue().asString();
            case ARRAY -> {
                ArrayValue arr = value.asArrayValue();
                List<Object> list = new ArrayList<>(arr.size());
                for (Value item : arr) {
                    list.add(valueToObject(item, depth + 1));
                }
                yield list;
            }
            case MAP -> valueToMap(value.asMapValue(), depth + 1);
            case BINARY -> value.asBinaryValue().asByteArray();
            case EXTENSION -> value.toString();
        };
    }
}
