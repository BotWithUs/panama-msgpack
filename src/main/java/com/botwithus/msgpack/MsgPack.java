package com.botwithus.msgpack;

import java.lang.foreign.MemorySegment;

/**
 * One-call encode and decode of whole values. For anything else, such as
 * streaming headers, reading in place, or a caller's own object model, use
 * {@link MsgPacker} and {@link MsgUnpacker} directly.
 */
public final class MsgPack {

    private MsgPack() {
    }

    /** @return {@code value} packed with the smallest encoding of each part */
    public static byte[] encode(MsgValue value) {
        return new MsgPacker().packValue(value).toByteArray();
    }

    /** @return the one value in {@code data}, under {@link MsgDecodeRules#strict()} */
    public static MsgValue decode(byte[] data) {
        return decode(MemorySegment.ofArray(data), MsgDecodeRules.strict());
    }

    /** @return the first value in {@code data}, under {@code rules} */
    public static MsgValue decode(byte[] data, MsgDecodeRules rules) {
        return decode(MemorySegment.ofArray(data), rules);
    }

    /**
     * @return the first value in {@code data}, under {@code rules}
     * @throws MsgPackException if the input is malformed or breaks a rule
     */
    public static MsgValue decode(MemorySegment data, MsgDecodeRules rules) {
        MsgUnpacker unpacker = new MsgUnpacker(data);
        if (rules.isMapRootRequired() && unpacker.nextType() != MsgType.MAP) {
            throw new MsgPackException("the root value is a " + unpacker.nextType() + ", not a map");
        }
        MsgValue value = unpacker.unpackValue(rules.maxDepth());
        if (rules.isTrailingDataRejected() && unpacker.hasNext()) {
            throw new MsgPackException(unpacker.remaining() + " trailing bytes after the value");
        }
        return value;
    }

    /**
     * @return the map {@code data} holds, under {@code rules} with a map root required
     * @throws MsgPackException if the input is malformed, breaks a rule or is not a map
     */
    public static MsgMap decodeMap(MemorySegment data, MsgDecodeRules rules) {
        return switch (decode(data, rules.requiringMapRoot())) {
            case MsgMap map -> map;
            default -> throw new MsgPackException("the root value is not a map");
        };
    }

    /** @return the map {@code data} holds, under {@code rules} with a map root required */
    public static MsgMap decodeMap(byte[] data, MsgDecodeRules rules) {
        return decodeMap(MemorySegment.ofArray(data), rules);
    }
}
