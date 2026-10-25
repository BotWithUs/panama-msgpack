package com.botwithus.msgpack;

import org.msgpack.value.Value;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Canonical text for decoded values, so two decoders' outputs compare exactly:
 * Java type, float bits, raw string bytes and map entry order all included.
 */
final class Canon {

    private static final HexFormat HEX = HexFormat.of();

    private Canon() {
    }

    /** @return the canonical text of a {@code Map<String, Object>}-codec value */
    static String ofJava(Object value) {
        return switch (value) {
            case null -> "nil";
            case Boolean b -> "bool:" + b;
            case Integer i -> "Integer:" + i;
            case Long l -> "Long:" + l;
            case Double d -> "Double:" + Long.toHexString(Double.doubleToRawLongBits(d));
            case String s -> "String:" + HEX.formatHex(s.getBytes(StandardCharsets.UTF_8));
            case byte[] b -> "bin:" + HEX.formatHex(b);
            case List<?> list -> {
                StringBuilder text = new StringBuilder("[");
                list.forEach(item -> text.append(ofJava(item)).append(','));
                yield text.append(']').toString();
            }
            case Map<?, ?> map -> {
                StringBuilder text = new StringBuilder("{");
                map.forEach((k, v) -> text.append(ofJava(k)).append('=').append(ofJava(v)).append(','));
                yield text.append('}').toString();
            }
            default -> "UNEXPECTED " + value.getClass().getName();
        };
    }

    /** @return the canonical text of this library's value tree, with timestamps read */
    static String ofMsg(MsgValue value) {
        return switch (value) {
            case MsgNil n -> "nil";
            case MsgBoolean b -> "bool:" + b.value();
            case MsgInteger i -> "int:" + i;
            case MsgFloat f -> "float:" + Long.toHexString(Double.doubleToRawLongBits(f.value()));
            case MsgString s -> "str:" + HEX.formatHex(s.toByteArray());
            case MsgBinary b -> "bin:" + HEX.formatHex(b.toByteArray());
            case MsgExtension e -> MsgTimestamps.isTimestamp(e)
                    ? "ts:" + MsgTimestamps.decode(e)
                    : "ext:" + e.extType() + ":" + HEX.formatHex(e.toByteArray());
            case MsgArray a -> {
                StringBuilder text = new StringBuilder("[");
                a.values().forEach(item -> text.append(ofMsg(item)).append(','));
                yield text.append(']').toString();
            }
            case MsgMap m -> {
                StringBuilder text = new StringBuilder("{");
                m.entries().forEach(e -> text.append(ofMsg(e.key())).append('=').append(ofMsg(e.value())).append(','));
                yield text.append('}').toString();
            }
        };
    }

    /** @return the canonical text of a msgpack-core value tree, in the same spelling as {@link #ofMsg} */
    static String ofCore(Value value) {
        return switch (value.getValueType()) {
            case NIL -> "nil";
            case BOOLEAN -> "bool:" + value.asBooleanValue().getBoolean();
            case INTEGER -> "int:" + value.asIntegerValue().asBigInteger();
            case FLOAT -> "float:" + Long.toHexString(Double.doubleToRawLongBits(value.asFloatValue().toDouble()));
            case STRING -> "str:" + HEX.formatHex(value.asStringValue().asByteArray());
            case BINARY -> "bin:" + HEX.formatHex(value.asBinaryValue().asByteArray());
            case EXTENSION -> value.isTimestampValue()
                    ? "ts:" + value.asTimestampValue().toInstant()
                    : "ext:" + value.asExtensionValue().getType() + ":"
                            + HEX.formatHex(value.asExtensionValue().getData());
            case ARRAY -> {
                StringBuilder text = new StringBuilder("[");
                value.asArrayValue().forEach(item -> text.append(ofCore(item)).append(','));
                yield text.append(']').toString();
            }
            case MAP -> {
                StringBuilder text = new StringBuilder("{");
                Value[] kv = value.asMapValue().getKeyValueArray();
                for (int i = 0; i < kv.length; i += 2) {
                    text.append(ofCore(kv[i])).append('=').append(ofCore(kv[i + 1])).append(',');
                }
                yield text.append('}').toString();
            }
        };
    }
}
