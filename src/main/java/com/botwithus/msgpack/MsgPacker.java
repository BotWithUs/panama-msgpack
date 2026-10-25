package com.botwithus.msgpack;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Writes MessagePack into a growable heap buffer, always choosing the smallest
 * encoding: the same bytes msgpack-java 0.9 writes for the same calls.
 *
 * <p>A packer is not thread-safe. Every {@code pack*} method returns the packer,
 * so calls chain. {@link #toByteArray()} and {@link #writeTo} copy the bytes
 * out; {@link #reset()} empties the packer for reuse.
 */
public final class MsgPacker {

    private static final int DEFAULT_CAPACITY = 256;
    /** Largest array the JVM will reliably allocate. */
    private static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;
    private static final int HEADER8 = 2;
    private static final int HEADER16 = 3;
    private static final int HEADER32 = 5;
    private static final int HEADER64 = 9;
    private static final int FIXEXT_HEADER = 2;

    private byte[] buffer;
    private MemorySegment segment;
    private int position;

    /** A packer with a small initial buffer. */
    public MsgPacker() {
        this(DEFAULT_CAPACITY);
    }

    /** @param initialCapacity the buffer's starting size in bytes; it grows as needed */
    public MsgPacker(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must not be negative: " + initialCapacity);
        }
        buffer = new byte[initialCapacity];
        segment = MemorySegment.ofArray(buffer);
    }

    /** @return the number of bytes written */
    public int size() {
        return position;
    }

    /** @return a copy of the bytes written */
    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, position);
    }

    /**
     * Copies the bytes written into {@code destination} at {@code offset}.
     *
     * @return the number of bytes copied
     */
    public int writeTo(MemorySegment destination, long offset) {
        MemorySegment.copy(segment, 0, destination, offset, position);
        return position;
    }

    /** Discards everything written, keeping the buffer. */
    public void reset() {
        position = 0;
    }

    /** @return this packer, after writing nil */
    public MsgPacker packNil() {
        return writeCode(Codes.NIL);
    }

    /** @return this packer, after writing {@code value} */
    public MsgPacker packBoolean(boolean value) {
        return writeCode(value ? Codes.TRUE : Codes.FALSE);
    }

    /** @return this packer, after writing {@code value} in its smallest integer format */
    public MsgPacker packInt(int value) {
        return packLong(value);
    }

    /**
     * Writes {@code value} in its smallest format. A negative value takes a
     * signed format and a positive one an unsigned format, as msgpack-java does.
     *
     * @return this packer
     */
    public MsgPacker packLong(long value) {
        if (value < Codes.NEGATIVE_FIXINT_FLOOR) {
            return packNegative(value);
        }
        if (value <= Codes.POSITIVE_FIXINT_MAX) {
            return writeCode((int) value);
        }
        return packPositive(value);
    }

    /**
     * Writes the integer whose unsigned 64-bit representation is {@code bits}.
     *
     * @return this packer
     */
    public MsgPacker packUnsignedLong(long bits) {
        if (bits >= 0) {
            return packLong(bits);
        }
        ensure(HEADER64);
        segment.set(BigEndian.BYTE, position, (byte) Codes.UINT64);
        segment.set(BigEndian.LONG, position + 1, bits);
        position += HEADER64;
        return this;
    }

    /** @return this packer, after writing {@code value} as a float32 */
    public MsgPacker packFloat(float value) {
        ensure(HEADER32);
        segment.set(BigEndian.BYTE, position, (byte) Codes.FLOAT32);
        segment.set(BigEndian.FLOAT, position + 1, value);
        position += HEADER32;
        return this;
    }

    /** @return this packer, after writing {@code value} as a float64 */
    public MsgPacker packDouble(double value) {
        ensure(HEADER64);
        segment.set(BigEndian.BYTE, position, (byte) Codes.FLOAT64);
        segment.set(BigEndian.DOUBLE, position + 1, value);
        position += HEADER64;
        return this;
    }

    /**
     * Writes {@code value} as UTF-8 under its smallest string header. A lone
     * surrogate is written as {@code '?'}.
     *
     * @return this packer
     */
    public MsgPacker packString(String value) {
        return packRawString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** @return this packer, after writing {@code utf8} as a string, unchecked */
    public MsgPacker packRawString(byte[] utf8) {
        packStringHeader(utf8.length);
        return writePayload(utf8);
    }

    /** @return this packer, after writing a string header for {@code length} bytes */
    public MsgPacker packStringHeader(int length) {
        requireLength(length);
        if (length < Codes.FIXSTR_LIMIT) {
            return writeCode(Codes.FIXSTR_PREFIX | length);
        }
        if (length < Codes.U8_LIMIT) {
            return writeHeader8(Codes.STR8, length);
        }
        return length < Codes.U16_LIMIT ? writeHeader16(Codes.STR16, length) : writeHeader32(Codes.STR32, length);
    }

    /** @return this packer, after writing {@code data} as binary */
    public MsgPacker packBinary(byte[] data) {
        packBinaryHeader(data.length);
        return writePayload(data);
    }

    /** @return this packer, after writing a binary header for {@code length} bytes */
    public MsgPacker packBinaryHeader(int length) {
        requireLength(length);
        if (length < Codes.U8_LIMIT) {
            return writeHeader8(Codes.BIN8, length);
        }
        return length < Codes.U16_LIMIT ? writeHeader16(Codes.BIN16, length) : writeHeader32(Codes.BIN32, length);
    }

    /** @return this packer, after writing an array header for {@code size} elements */
    public MsgPacker packArrayHeader(int size) {
        requireLength(size);
        if (size < Codes.FIXARRAY_LIMIT) {
            return writeCode(Codes.FIXARRAY_PREFIX | size);
        }
        return size < Codes.U16_LIMIT ? writeHeader16(Codes.ARRAY16, size) : writeHeader32(Codes.ARRAY32, size);
    }

    /** @return this packer, after writing a map header for {@code size} entries */
    public MsgPacker packMapHeader(int size) {
        requireLength(size);
        if (size < Codes.FIXMAP_LIMIT) {
            return writeCode(Codes.FIXMAP_PREFIX | size);
        }
        return size < Codes.U16_LIMIT ? writeHeader16(Codes.MAP16, size) : writeHeader32(Codes.MAP32, size);
    }

    /** @return this packer, after writing an extension of {@code extType} holding {@code data} */
    public MsgPacker packExtension(byte extType, byte[] data) {
        packExtensionHeader(extType, data.length);
        return writePayload(data);
    }

    /**
     * Writes an extension header in its smallest format: a fixext for a payload
     * of 1, 2, 4, 8 or 16 bytes, otherwise ext8, ext16 or ext32.
     *
     * @return this packer
     */
    public MsgPacker packExtensionHeader(byte extType, int length) {
        requireLength(length);
        int fixCode = fixextCode(length);
        if (fixCode != 0) {
            ensure(FIXEXT_HEADER);
            segment.set(BigEndian.BYTE, position, (byte) fixCode);
            segment.set(BigEndian.BYTE, position + 1, extType);
            position += FIXEXT_HEADER;
            return this;
        }
        return writeExtHeader(extType, length);
    }

    /** @return this packer, after copying {@code data} in with no header */
    public MsgPacker writePayload(byte[] data) {
        ensure(data.length);
        System.arraycopy(data, 0, buffer, position, data.length);
        position += data.length;
        return this;
    }

    /** @return this packer, after copying {@code data} in with no header */
    public MsgPacker writePayload(MemorySegment data) {
        int length = Math.toIntExact(data.byteSize());
        ensure(length);
        MemorySegment.copy(data, 0, segment, position, length);
        position += length;
        return this;
    }

    /**
     * Writes {@code value} and everything inside it. Each part takes its smallest
     * encoding; a float keeps its width and a string or binary its exact bytes.
     *
     * @return this packer
     */
    public MsgPacker packValue(MsgValue value) {
        return switch (value) {
            case MsgNil n -> packNil();
            case MsgBoolean b -> packBoolean(b.value());
            case MsgInteger i -> i.isUnsigned() ? packUnsignedLong(i.bits()) : packLong(i.bits());
            case MsgFloat f -> f.isSinglePrecision() ? packFloat((float) f.value()) : packDouble(f.value());
            case MsgString s -> packRawString(s.rawBytes());
            case MsgBinary b -> packBinary(b.rawBytes());
            case MsgExtension e -> packExtension(e.extType(), e.rawBytes());
            case MsgArray a -> packArray(a);
            case MsgMap m -> packMap(m);
        };
    }

    private MsgPacker packArray(MsgArray array) {
        packArrayHeader(array.size());
        for (MsgValue element : array.values()) {
            packValue(element);
        }
        return this;
    }

    private MsgPacker packMap(MsgMap map) {
        packMapHeader(map.size());
        for (MsgMap.Entry entry : map.entries()) {
            packValue(entry.key());
            packValue(entry.value());
        }
        return this;
    }

    private MsgPacker packNegative(long value) {
        if (value >= Byte.MIN_VALUE) {
            return writeHeader8(Codes.INT8, (int) value);
        }
        if (value >= Short.MIN_VALUE) {
            return writeHeader16(Codes.INT16, (int) value);
        }
        return value >= Integer.MIN_VALUE ? writeHeader32(Codes.INT32, (int) value) : writeHeader64(Codes.INT64, value);
    }

    private MsgPacker packPositive(long value) {
        if (value < Codes.U8_LIMIT) {
            return writeHeader8(Codes.UINT8, (int) value);
        }
        if (value < Codes.U16_LIMIT) {
            return writeHeader16(Codes.UINT16, (int) value);
        }
        return value < Codes.U32_LIMIT ? writeHeader32(Codes.UINT32, (int) value) : writeHeader64(Codes.UINT64, value);
    }

    private MsgPacker writeExtHeader(byte extType, int length) {
        if (length < Codes.U8_LIMIT) {
            writeHeader8(Codes.EXT8, length);
        } else if (length < Codes.U16_LIMIT) {
            writeHeader16(Codes.EXT16, length);
        } else {
            writeHeader32(Codes.EXT32, length);
        }
        ensure(1);
        segment.set(BigEndian.BYTE, position++, extType);
        return this;
    }

    private static int fixextCode(int length) {
        return switch (length) {
            case Codes.FIXEXT1_LENGTH -> Codes.FIXEXT1;
            case Codes.FIXEXT2_LENGTH -> Codes.FIXEXT2;
            case Codes.FIXEXT4_LENGTH -> Codes.FIXEXT4;
            case Codes.FIXEXT8_LENGTH -> Codes.FIXEXT8;
            case Codes.FIXEXT16_LENGTH -> Codes.FIXEXT16;
            default -> 0;
        };
    }

    private MsgPacker writeCode(int code) {
        ensure(1);
        segment.set(BigEndian.BYTE, position++, (byte) code);
        return this;
    }

    private MsgPacker writeHeader8(int code, int value) {
        ensure(HEADER8);
        segment.set(BigEndian.BYTE, position, (byte) code);
        segment.set(BigEndian.BYTE, position + 1, (byte) value);
        position += HEADER8;
        return this;
    }

    private MsgPacker writeHeader16(int code, int value) {
        ensure(HEADER16);
        segment.set(BigEndian.BYTE, position, (byte) code);
        segment.set(BigEndian.SHORT, position + 1, (short) value);
        position += HEADER16;
        return this;
    }

    private MsgPacker writeHeader32(int code, int value) {
        ensure(HEADER32);
        segment.set(BigEndian.BYTE, position, (byte) code);
        segment.set(BigEndian.INT, position + 1, value);
        position += HEADER32;
        return this;
    }

    private MsgPacker writeHeader64(int code, long value) {
        ensure(HEADER64);
        segment.set(BigEndian.BYTE, position, (byte) code);
        segment.set(BigEndian.LONG, position + 1, value);
        position += HEADER64;
        return this;
    }

    private static void requireLength(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative: " + length);
        }
    }

    private void ensure(int extra) {
        if (extra <= buffer.length - position) {
            return;
        }
        long needed = (long) position + extra;
        if (needed > MAX_CAPACITY) {
            throw new MsgPackException("packed output would exceed " + MAX_CAPACITY + " bytes");
        }
        int grown = (int) Math.min(MAX_CAPACITY, Math.max(needed, (long) buffer.length * 2));
        buffer = Arrays.copyOf(buffer, grown);
        segment = MemorySegment.ofArray(buffer);
    }
}
