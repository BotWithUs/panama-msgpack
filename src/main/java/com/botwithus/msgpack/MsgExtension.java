package com.botwithus.msgpack;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * A MessagePack extension value: an application type code and an opaque
 * payload. Type {@code -1} is the spec's timestamp, which {@link MsgTimestamps}
 * reads. Every other type is left to the application.
 */
public final class MsgExtension implements MsgValue {

    private static final int HASH_MULTIPLIER = 31;

    private final byte extType;
    private final byte[] data;

    private MsgExtension(byte extType, byte[] data) {
        this.extType = extType;
        this.data = data;
    }

    /** @return an extension of {@code extType} holding a copy of {@code data} */
    public static MsgExtension of(byte extType, byte[] data) {
        return new MsgExtension(extType, data.clone());
    }

    /** Takes ownership of {@code data}, for the unpacker, which has just copied it. */
    static MsgExtension wrap(byte extType, byte[] data) {
        return new MsgExtension(extType, data);
    }

    @Override
    public MsgType type() {
        return MsgType.EXTENSION;
    }

    /** @return the application type code */
    public byte extType() {
        return extType;
    }

    /** @return a copy of the payload */
    public byte[] toByteArray() {
        return data.clone();
    }

    /** @return the payload length */
    public int length() {
        return data.length;
    }

    /** The internal array, never handed out. The packer writes it directly. */
    byte[] rawBytes() {
        return data;
    }

    @Override
    public boolean equals(Object other) {
        return switch (other) {
            case MsgExtension e -> extType == e.extType && Arrays.equals(data, e.data);
            case null, default -> false;
        };
    }

    @Override
    public int hashCode() {
        return Byte.hashCode(extType) * HASH_MULTIPLIER + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "ext(" + extType + ")[" + HexFormat.of().formatHex(data) + "]";
    }
}
