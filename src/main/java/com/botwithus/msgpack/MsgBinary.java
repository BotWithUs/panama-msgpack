package com.botwithus.msgpack;

import java.util.Arrays;
import java.util.HexFormat;

/** A MessagePack binary, compared by content. */
public final class MsgBinary implements MsgValue {

    private final byte[] data;

    private MsgBinary(byte[] data) {
        this.data = data;
    }

    /** @return a binary holding a copy of {@code data} */
    public static MsgBinary of(byte[] data) {
        return new MsgBinary(data.clone());
    }

    /** Takes ownership of {@code data}, for the unpacker, which has just copied it. */
    static MsgBinary wrap(byte[] data) {
        return new MsgBinary(data);
    }

    @Override
    public MsgType type() {
        return MsgType.BINARY;
    }

    /** @return a copy of the bytes */
    public byte[] toByteArray() {
        return data.clone();
    }

    /** @return the number of bytes */
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
            case MsgBinary b -> Arrays.equals(data, b.data);
            case null, default -> false;
        };
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "bin[" + HexFormat.of().formatHex(data) + "]";
    }
}
