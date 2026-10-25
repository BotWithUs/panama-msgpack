package com.botwithus.msgpack;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A MessagePack string. It holds the bytes as they arrived, because the format
 * does not guarantee they are UTF-8. A decoded string packs back to the same
 * bytes; only {@link #asString()} insists that they decode.
 */
public final class MsgString implements MsgValue {

    private final byte[] utf8;

    private MsgString(byte[] utf8) {
        this.utf8 = utf8;
    }

    /**
     * @return {@code value} encoded as UTF-8. A lone surrogate becomes {@code '?'},
     *         as {@link String#getBytes} encodes it
     */
    public static MsgString of(String value) {
        return new MsgString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** @return a string holding a copy of {@code bytes}, which need not be valid UTF-8 */
    public static MsgString ofBytes(byte[] bytes) {
        return new MsgString(bytes.clone());
    }

    /** Takes ownership of {@code bytes}, for the unpacker, which has just copied them. */
    static MsgString wrap(byte[] bytes) {
        return new MsgString(bytes);
    }

    @Override
    public MsgType type() {
        return MsgType.STRING;
    }

    /**
     * @return the string
     * @throws MsgPackException if the bytes are not valid UTF-8
     */
    public String asString() {
        return decodeStrict(utf8);
    }

    /** @return a copy of the raw bytes */
    public byte[] toByteArray() {
        return utf8.clone();
    }

    /** @return the length of the raw bytes */
    public int byteLength() {
        return utf8.length;
    }

    /** The internal array, never handed out. The packer writes it directly. */
    byte[] rawBytes() {
        return utf8;
    }

    /**
     * @return {@code bytes} decoded as UTF-8
     * @throws MsgPackException if they are not valid UTF-8
     */
    static String decodeStrict(byte[] bytes) {
        if (isAscii(bytes)) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new MsgPackException("string is not valid UTF-8", e);
        }
    }

    private static boolean isAscii(byte[] bytes) {
        for (byte b : bytes) {
            if (b < 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object other) {
        return switch (other) {
            case MsgString s -> Arrays.equals(utf8, s.utf8);
            case null, default -> false;
        };
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(utf8);
    }

    /** @return the string, with any invalid UTF-8 replaced, for logs */
    @Override
    public String toString() {
        return new String(utf8, StandardCharsets.UTF_8);
    }
}
