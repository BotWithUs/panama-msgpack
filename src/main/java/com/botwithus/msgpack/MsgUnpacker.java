package com.botwithus.msgpack;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads MessagePack from a heap array or any {@link MemorySegment}, one value
 * or one header at a time, in a single forward pass.
 *
 * <p>Every read is bounds-checked first. A frame that ends early, or a length
 * or count larger than the bytes left, throws {@link MsgPackException} before
 * anything is allocated for it. A read of the wrong type throws without moving
 * the cursor. Every encoding the specification allows is accepted, whether or
 * not it is the smallest, so a value in int32 or a short string in str32 reads
 * the same as its minimal form.
 *
 * <p>An unpacker is not thread-safe. Reading from a segment the caller owns
 * leaves that segment's lifetime and confinement to the caller.
 */
public final class MsgUnpacker {

    private static final int MAP_ENTRY_MIN_BYTES = 2;

    private final MemorySegment in;
    private final long limit;
    private long position;

    /** @param data the bytes to read; not copied, so keep them unchanged while reading */
    public MsgUnpacker(byte[] data) {
        this(MemorySegment.ofArray(data));
    }

    /** @param data the segment to read, from offset 0 to its end */
    public MsgUnpacker(MemorySegment data) {
        in = data;
        limit = data.byteSize();
    }

    /** @return whether any bytes are left */
    public boolean hasNext() {
        return position < limit;
    }

    /** @return the offset of the next byte to read */
    public long position() {
        return position;
    }

    /** @return the number of bytes left */
    public long remaining() {
        return limit - position;
    }

    /**
     * @return the type of the next value, without moving the cursor
     * @throws MsgPackException at the end of input, or on the never-used byte {@code 0xc1}
     */
    public MsgType nextType() {
        return Codes.typeOf(peekCode());
    }

    /** Reads a nil. */
    public void unpackNil() {
        expect(MsgType.NIL);
        position++;
    }

    /** @return the boolean read */
    public boolean unpackBoolean() {
        expect(MsgType.BOOLEAN);
        return readU8() == Codes.TRUE;
    }

    /** @return the integer read, in any of the integer formats */
    public MsgInteger unpackInteger() {
        int code = expect(MsgType.INTEGER);
        position++;
        if (code <= Codes.POSITIVE_FIXINT_MAX) {
            return MsgInteger.of(code);
        }
        if (code >= Codes.NEGATIVE_FIXINT_MIN) {
            return MsgInteger.of((byte) code);
        }
        return switch (code) {
            case Codes.UINT8 -> MsgInteger.of(readU8());
            case Codes.UINT16 -> MsgInteger.of(readU16());
            case Codes.UINT32 -> MsgInteger.of(readU32());
            case Codes.UINT64 -> MsgInteger.ofUnsigned(readI64());
            case Codes.INT8 -> MsgInteger.of(readI8());
            case Codes.INT16 -> MsgInteger.of(readI16());
            case Codes.INT32 -> MsgInteger.of(readI32());
            default -> MsgInteger.of(readI64());
        };
    }

    /**
     * @return the integer read
     * @throws MsgPackException if it is above {@link Long#MAX_VALUE}
     */
    public long unpackLong() {
        return unpackInteger().asLong();
    }

    /**
     * @return the integer read
     * @throws MsgPackException if it does not fit an {@code int}
     */
    public int unpackInt() {
        return unpackInteger().asInt();
    }

    /** @return the float read, keeping its width */
    public MsgFloat unpackFloatValue() {
        int code = expect(MsgType.FLOAT);
        position++;
        if (code == Codes.FLOAT32) {
            require(Float.BYTES);
            float value = in.get(BigEndian.FLOAT, position);
            position += Float.BYTES;
            return MsgFloat.ofSingle(value);
        }
        require(Double.BYTES);
        double value = in.get(BigEndian.DOUBLE, position);
        position += Double.BYTES;
        return MsgFloat.of(value);
    }

    /** @return the float32 or float64 read, widened to a double */
    public double unpackDouble() {
        return unpackFloatValue().value();
    }

    /**
     * @return the byte length of the string that follows; the bytes are left unread
     * @throws MsgPackException if the length is larger than the bytes left
     */
    public int unpackStringHeader() {
        int code = expect(MsgType.STRING);
        position++;
        if (code <= Codes.FIXSTR_MAX) {
            return checkedLength(code & Codes.FIXSTR_MASK);
        }
        return switch (code) {
            case Codes.STR8 -> checkedLength(readU8());
            case Codes.STR16 -> checkedLength(readU16());
            default -> checkedLength(readU32());
        };
    }

    /**
     * @return the string read
     * @throws MsgPackException if its bytes are not valid UTF-8
     */
    public String unpackString() {
        return MsgString.decodeStrict(readPayload(unpackStringHeader()));
    }

    /** @return the string read, as its raw bytes, which need not be valid UTF-8 */
    public MsgString unpackStringValue() {
        return MsgString.wrap(readPayload(unpackStringHeader()));
    }

    /**
     * @return the byte length of the binary that follows; the bytes are left unread
     * @throws MsgPackException if the length is larger than the bytes left
     */
    public int unpackBinaryHeader() {
        int code = expect(MsgType.BINARY);
        position++;
        return switch (code) {
            case Codes.BIN8 -> checkedLength(readU8());
            case Codes.BIN16 -> checkedLength(readU16());
            default -> checkedLength(readU32());
        };
    }

    /** @return a copy of the binary read */
    public byte[] unpackBinary() {
        return readPayload(unpackBinaryHeader());
    }

    /**
     * @return the element count of the array that follows
     * @throws MsgPackException if the count is larger than the bytes left could hold
     */
    public int unpackArrayHeader() {
        int code = expect(MsgType.ARRAY);
        position++;
        long count = code <= Codes.FIXARRAY_MAX ? code & Codes.FIXMAP_MASK
                : code == Codes.ARRAY16 ? readU16() : readU32();
        return checkedCount(count, 1, "array");
    }

    /**
     * @return the entry count of the map that follows
     * @throws MsgPackException if the count is larger than the bytes left could hold
     */
    public int unpackMapHeader() {
        int code = expect(MsgType.MAP);
        position++;
        long count = code <= Codes.FIXMAP_MAX ? code & Codes.FIXMAP_MASK
                : code == Codes.MAP16 ? readU16() : readU32();
        return checkedCount(count, MAP_ENTRY_MIN_BYTES, "map");
    }

    /**
     * @return the type code and payload length of the extension that follows; the payload is left unread
     * @throws MsgPackException if the length is larger than the bytes left
     */
    public MsgExtensionHeader unpackExtensionHeader() {
        int code = expect(MsgType.EXTENSION);
        position++;
        long length = switch (code) {
            case Codes.FIXEXT1 -> Codes.FIXEXT1_LENGTH;
            case Codes.FIXEXT2 -> Codes.FIXEXT2_LENGTH;
            case Codes.FIXEXT4 -> Codes.FIXEXT4_LENGTH;
            case Codes.FIXEXT8 -> Codes.FIXEXT8_LENGTH;
            case Codes.FIXEXT16 -> Codes.FIXEXT16_LENGTH;
            case Codes.EXT8 -> readU8();
            case Codes.EXT16 -> readU16();
            default -> readU32();
        };
        byte extType = readI8();
        return new MsgExtensionHeader(extType, checkedLength(length));
    }

    /** @return the extension read */
    public MsgExtension unpackExtension() {
        MsgExtensionHeader header = unpackExtensionHeader();
        return MsgExtension.wrap(header.extType(), readPayload(header.length()));
    }

    /**
     * @return a copy of the next {@code length} bytes, read with no header
     * @throws MsgPackException if fewer bytes are left
     */
    public byte[] readPayload(int length) {
        requireNonNegative(length);
        require(length);
        byte[] out = new byte[length];
        MemorySegment.copy(in, BigEndian.BYTE, position, out, 0, length);
        position += length;
        return out;
    }

    /**
     * @return a view of the next {@code length} bytes, with no copy. It shares the
     *         input's lifetime, and changes to the input show through it
     * @throws MsgPackException if fewer bytes are left
     */
    public MemorySegment readPayloadSegment(int length) {
        requireNonNegative(length);
        require(length);
        MemorySegment view = in.asSlice(position, length);
        position += length;
        return view;
    }

    /**
     * Steps over the next value and everything inside it, without building it.
     * It keeps a count rather than recursing, so no nesting depth can overflow it.
     */
    public void skipValue() {
        long pending = 1;
        while (pending > 0) {
            pending--;
            pending += skipOne();
        }
    }

    /**
     * Reads the next value and everything inside it.
     *
     * @param maxDepth how deeply containers may nest: 0 allows only a scalar, 1
     *                 allows a container of scalars, and so on
     * @return the value read
     * @throws MsgPackException if a container would open past {@code maxDepth};
     *                          the check runs before the container is read
     */
    public MsgValue unpackValue(int maxDepth) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must not be negative: " + maxDepth);
        }
        return readValue(0, maxDepth);
    }

    private MsgValue readValue(int depth, int maxDepth) {
        return switch (nextType()) {
            case NIL -> {
                unpackNil();
                yield new MsgNil();
            }
            case BOOLEAN -> new MsgBoolean(unpackBoolean());
            case INTEGER -> unpackInteger();
            case FLOAT -> unpackFloatValue();
            case STRING -> unpackStringValue();
            case BINARY -> MsgBinary.wrap(unpackBinary());
            case EXTENSION -> unpackExtension();
            case ARRAY -> readArray(enter(depth, maxDepth), maxDepth);
            case MAP -> readMap(enter(depth, maxDepth), maxDepth);
        };
    }

    private MsgArray readArray(int depth, int maxDepth) {
        int count = unpackArrayHeader();
        List<MsgValue> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(readValue(depth, maxDepth));
        }
        return new MsgArray(values);
    }

    private MsgMap readMap(int depth, int maxDepth) {
        int count = unpackMapHeader();
        List<MsgMap.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            MsgValue key = readValue(depth, maxDepth);
            entries.add(new MsgMap.Entry(key, readValue(depth, maxDepth)));
        }
        return new MsgMap(entries);
    }

    private static int enter(int depth, int maxDepth) {
        int inner = depth + 1;
        if (inner > maxDepth) {
            throw new MsgPackException("MessagePack nesting exceeds " + maxDepth + " levels");
        }
        return inner;
    }

    /** @return how many child values the value just stepped over still owes */
    private long skipOne() {
        return switch (nextType()) {
            case ARRAY -> unpackArrayHeader();
            case MAP -> (long) unpackMapHeader() * MAP_ENTRY_MIN_BYTES;
            case STRING -> skipPayload(unpackStringHeader());
            case BINARY -> skipPayload(unpackBinaryHeader());
            case EXTENSION -> skipPayload(unpackExtensionHeader().length());
            case FLOAT -> {
                unpackFloatValue();
                yield 0;
            }
            case INTEGER -> {
                unpackInteger();
                yield 0;
            }
            case NIL, BOOLEAN -> {
                position++;
                yield 0;
            }
        };
    }

    private long skipPayload(int length) {
        position += length;
        return 0;
    }

    private int expect(MsgType wanted) {
        int code = peekCode();
        MsgType actual = Codes.typeOf(code);
        if (actual != wanted) {
            throw new MsgPackException("expected " + wanted + " at offset " + position + " but found " + actual
                    + " (0x" + Integer.toHexString(code) + ")");
        }
        return code;
    }

    private int peekCode() {
        require(1);
        return in.get(BigEndian.BYTE, position) & Codes.U8_MASK;
    }

    private int readU8() {
        return readI8() & Codes.U8_MASK;
    }

    private byte readI8() {
        require(Byte.BYTES);
        return in.get(BigEndian.BYTE, position++);
    }

    private int readU16() {
        return readI16() & Codes.U16_MASK;
    }

    private short readI16() {
        require(Short.BYTES);
        short value = in.get(BigEndian.SHORT, position);
        position += Short.BYTES;
        return value;
    }

    private long readU32() {
        return readI32() & Codes.U32_MASK;
    }

    private int readI32() {
        require(Integer.BYTES);
        int value = in.get(BigEndian.INT, position);
        position += Integer.BYTES;
        return value;
    }

    private long readI64() {
        require(Long.BYTES);
        long value = in.get(BigEndian.LONG, position);
        position += Long.BYTES;
        return value;
    }

    private int checkedLength(long length) {
        if (length > remaining()) {
            throw new MsgPackException("length " + length + " at offset " + position
                    + " exceeds the " + remaining() + " bytes left");
        }
        return toJavaSize(length);
    }

    private int checkedCount(long count, int minBytesEach, String what) {
        if (count * minBytesEach > remaining()) {
            throw new MsgPackException(what + " of " + count + " at offset " + position
                    + " cannot fit in the " + remaining() + " bytes left");
        }
        return toJavaSize(count);
    }

    /** A length the input can hold may still be too large for a Java array, on a big segment. */
    private static int toJavaSize(long size) {
        if (size > Integer.MAX_VALUE) {
            throw new MsgPackException("length " + size + " is larger than a Java array can hold");
        }
        return (int) size;
    }

    private void require(long bytes) {
        if (bytes > remaining()) {
            throw new MsgPackException("MessagePack input truncated: need " + bytes + " bytes at offset "
                    + position + ", " + remaining() + " left");
        }
    }

    private static void requireNonNegative(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative: " + length);
        }
    }
}
