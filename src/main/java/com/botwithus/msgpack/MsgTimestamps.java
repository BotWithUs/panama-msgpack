package com.botwithus.msgpack;

import java.lang.foreign.MemorySegment;
import java.time.DateTimeException;
import java.time.Instant;

/**
 * The timestamp extension of the MessagePack specification (type {@code -1}).
 * The library never decodes one on its own: a decoded timestamp is an ordinary
 * {@link MsgExtension} until a caller asks for it here.
 */
public final class MsgTimestamps {

    /** The extension type code the specification reserves for timestamps. */
    public static final byte TIMESTAMP_TYPE = -1;

    private static final int TIMESTAMP32_LENGTH = 4;
    private static final int TIMESTAMP64_LENGTH = 8;
    private static final int TIMESTAMP96_LENGTH = 12;
    /** timestamp64 packs the nanoseconds above a 34-bit seconds field. */
    private static final int TIMESTAMP64_SECONDS_BITS = 34;
    private static final long TIMESTAMP64_SECONDS_MASK = (1L << TIMESTAMP64_SECONDS_BITS) - 1;

    private MsgTimestamps() {
    }

    /** @return whether {@code value} is a timestamp extension */
    public static boolean isTimestamp(MsgExtension value) {
        return value.extType() == TIMESTAMP_TYPE;
    }

    /**
     * @return the instant a timestamp extension holds
     * @throws MsgPackException if it is not a timestamp, its payload is not 4, 8
     *                          or 12 bytes, or the instant is out of range
     */
    public static Instant decode(MsgExtension value) {
        if (!isTimestamp(value)) {
            throw new MsgPackException("extension type " + value.extType() + " is not a timestamp");
        }
        return decodePayload(value.rawBytes());
    }

    /**
     * @return the instant a timestamp payload holds; the extension type is the caller's to check
     * @throws MsgPackException if the payload is not 4, 8 or 12 bytes, or the instant is out of range
     */
    public static Instant decodePayload(byte[] payload) {
        MemorySegment in = MemorySegment.ofArray(payload);
        try {
            return switch (payload.length) {
                case TIMESTAMP32_LENGTH -> Instant.ofEpochSecond(in.get(BigEndian.INT, 0) & Codes.U32_MASK);
                case TIMESTAMP64_LENGTH -> {
                    long data64 = in.get(BigEndian.LONG, 0);
                    yield Instant.ofEpochSecond(data64 & TIMESTAMP64_SECONDS_MASK,
                            data64 >>> TIMESTAMP64_SECONDS_BITS);
                }
                case TIMESTAMP96_LENGTH -> Instant.ofEpochSecond(in.get(BigEndian.LONG, Integer.BYTES),
                        in.get(BigEndian.INT, 0) & Codes.U32_MASK);
                default -> throw new MsgPackException("a timestamp payload is 4, 8 or 12 bytes, not "
                        + payload.length);
            };
        } catch (DateTimeException | ArithmeticException e) {
            throw new MsgPackException("timestamp is out of range", e);
        }
    }
}
