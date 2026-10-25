package com.botwithus.msgpack;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Seeded random inputs: Java maps to encode, and hand-written frames to decode. */
final class Gen {

    /**
     * What a generated frame may contain beyond plain well-formed values.
     *
     * @param hasBadTimestamps  timestamp extensions whose payload is not 4, 8 or 12 bytes
     * @param hasInvalidUtf8    strings whose bytes are not valid UTF-8
     * @param hasNonStringKeys  map keys of types other than string
     * @param hasHugeUnsigned   uint64 values above {@code Long.MAX_VALUE}
     * @param maxDepth          how deeply containers nest
     * @param maxStringLength   the longest string or binary generated
     */
    record Options(boolean hasBadTimestamps, boolean hasInvalidUtf8, boolean hasNonStringKeys,
                   boolean hasHugeUnsigned, int maxDepth, int maxStringLength) {

        static Options wellFormed() {
            return new Options(false, false, false, false, 4, 300);
        }

        static Options everything() {
            return new Options(true, true, true, true, 4, 300);
        }
    }

    static final long[] INTEGER_BOUNDARIES = {
        0, 1, -1, 31, 32, -31, -32, -33, 127, 128, -127, -128, -129, 255, 256, 32767, 32768,
        -32767, -32768, -32769, 65535, 65536, Integer.MAX_VALUE, Integer.MAX_VALUE + 1L,
        Integer.MIN_VALUE, Integer.MIN_VALUE - 1L, 4294967295L, 4294967296L,
        Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1,
    };

    static final int[] LENGTH_BOUNDARIES = {0, 1, 15, 16, 31, 32, 255, 256, 511, 512, 513, 65535, 65536};

    /** Code points across the UTF-8 widths, plus the two lone surrogates. */
    private static final int[] CODE_POINTS = {
        'a', 'Z', '0', ' ', '"', 0x00, 0x1f, 0x7f, 0xe9, 0x7ff, 0x800, 0x4e2d, 0xfffd, 0xffff,
        0x10000, 0x1f600, 0x10ffff, 0xd800, 0xdfff,
    };

    private static final byte TIMESTAMP = -1;
    private static final int[] TIMESTAMP_LENGTHS = {4, 8, 12};
    private static final int[] FIXEXT_LENGTHS = {1, 2, 4, 8, 16};
    private static final int VALUE_KINDS = 11;
    private static final int SCALAR_KINDS = 9;

    private Gen() {
    }

    // ---- Java maps for the encode side ----------------------------------

    static Map<String, Object> javaMap(Random r, int depth) {
        int size = r.nextInt(8);
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            map.put(javaString(r, 12), javaValue(r, depth));
        }
        return map;
    }

    private static Object javaValue(Random r, int depth) {
        int kinds = depth > 0 ? 13 : 11;
        return switch (r.nextInt(kinds)) {
            case 0 -> null;
            case 1 -> r.nextBoolean();
            case 2 -> (int) integer(r);
            case 3 -> integer(r);
            case 4 -> floatValue(r);
            case 5 -> doubleValue(r);
            case 6 -> javaString(r, 40);
            case 7 -> javaString(r, LENGTH_BOUNDARIES[r.nextInt(LENGTH_BOUNDARIES.length)]);
            case 8 -> otherObject(r);
            case 9 -> r.nextInt(2) == 0 ? Integer.valueOf(r.nextInt()) : Long.valueOf(r.nextLong());
            case 10 -> new byte[] {1, 2, 3};
            case 11 -> javaMap(r, depth - 1);
            default -> javaList(r, depth - 1);
        };
    }

    private static List<Object> javaList(Random r, int depth) {
        int size = r.nextInt(5) == 0 ? 16 + r.nextInt(4) : r.nextInt(6);
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(javaValue(r, depth));
        }
        return list;
    }

    /** Types the codec has no case for, which fall back to {@code toString()}. */
    private static Object otherObject(Random r) {
        return switch (r.nextInt(5)) {
            case 0 -> (short) r.nextInt();
            case 1 -> (byte) r.nextInt();
            case 2 -> new BigInteger(70, r);
            case 3 -> 'x';
            default -> new StringBuilder("sb").append(r.nextInt(100));
        };
    }

    static long integer(Random r) {
        if (r.nextInt(3) == 0) {
            return r.nextLong();
        }
        return INTEGER_BOUNDARIES[r.nextInt(INTEGER_BOUNDARIES.length)];
    }

    static float floatValue(Random r) {
        float[] special = {0f, -0f, Float.NaN, Float.POSITIVE_INFINITY, Float.MIN_VALUE, Float.MAX_VALUE,
            Float.intBitsToFloat(0x7fc00001)};
        return r.nextBoolean() ? special[r.nextInt(special.length)] : Float.intBitsToFloat(r.nextInt());
    }

    static double doubleValue(Random r) {
        double[] special = {0d, -0d, Double.NaN, Double.NEGATIVE_INFINITY, Double.MIN_VALUE, Double.MAX_VALUE,
            Double.longBitsToDouble(0x7ff8000000000001L)};
        return r.nextBoolean() ? special[r.nextInt(special.length)] : Double.longBitsToDouble(r.nextLong());
    }

    /** A string of {@code length} UTF-16 units at most, drawn across every UTF-8 width. */
    static String javaString(Random r, int length) {
        StringBuilder text = new StringBuilder();
        while (text.length() < length) {
            text.appendCodePoint(CODE_POINTS[r.nextInt(CODE_POINTS.length)]);
        }
        text.setLength(length);
        return text.toString();
    }

    // ---- hand-written frames for the decode side -------------------------

    /** @return a frame whose root is a map, every part in a randomly chosen legal encoding */
    static byte[] frame(Random r, Options options) {
        RawWriter w = new RawWriter();
        int size = r.nextInt(8);
        w.mapHeader(size, countFamily(r, size));
        for (int i = 0; i < size; i++) {
            key(w, r, options);
            value(w, r, options, 1);
        }
        return w.toByteArray();
    }

    static void value(RawWriter w, Random r, Options options, int depth) {
        int kinds = depth >= options.maxDepth() ? SCALAR_KINDS : VALUE_KINDS;
        switch (r.nextInt(kinds)) {
            case 0 -> w.u8(Codes.NIL);
            case 1 -> w.u8(r.nextBoolean() ? Codes.TRUE : Codes.FALSE);
            case 2, 3 -> integer(w, r, options);
            case 4 -> w.f32(floatValue(r));
            case 5 -> w.f64(doubleValue(r));
            case 6 -> string(w, r, options);
            case 7 -> binary(w, r, options);
            case 8 -> extension(w, r, options);
            case 9 -> array(w, r, options, depth + 1);
            default -> map(w, r, options, depth + 1);
        }
    }

    private static void key(RawWriter w, Random r, Options options) {
        if (options.hasNonStringKeys() && r.nextInt(20) == 0) {
            integer(w, r, options);
        } else {
            w.str(("k" + r.nextInt(6)).getBytes(StandardCharsets.UTF_8), r.nextBoolean() ? 0 : Short.SIZE);
        }
    }

    private static void array(RawWriter w, Random r, Options options, int depth) {
        int size = r.nextInt(5);
        w.arrayHeader(size, countFamily(r, size));
        for (int i = 0; i < size; i++) {
            value(w, r, options, depth);
        }
    }

    private static void map(RawWriter w, Random r, Options options, int depth) {
        int size = r.nextInt(4);
        w.mapHeader(size, countFamily(r, size));
        for (int i = 0; i < size; i++) {
            key(w, r, options);
            value(w, r, options, depth);
        }
    }

    /** Writes an integer in any format wide enough for it, minimal or not. */
    static void integer(RawWriter w, Random r, Options options) {
        if (options.hasHugeUnsigned() && r.nextInt(30) == 0) {
            w.u8(Codes.UINT64).u64(Long.MIN_VALUE | r.nextLong());
            return;
        }
        long v = integer(r);
        List<Runnable> fits = new ArrayList<>();
        if (v >= 0 && v <= Codes.POSITIVE_FIXINT_MAX || v >= Codes.NEGATIVE_FIXINT_FLOOR && v < 0) {
            fits.add(() -> w.u8((int) v));
        }
        if (v >= 0) {
            addUnsigned(w, v, fits);
        }
        addSigned(w, v, fits);
        fits.get(r.nextInt(fits.size())).run();
    }

    private static void addUnsigned(RawWriter w, long v, List<Runnable> fits) {
        if (v < Codes.U8_LIMIT) {
            fits.add(() -> w.u8(Codes.UINT8).u8((int) v));
        }
        if (v < Codes.U16_LIMIT) {
            fits.add(() -> w.u8(Codes.UINT16).u16((int) v));
        }
        if (v < Codes.U32_LIMIT) {
            fits.add(() -> w.u8(Codes.UINT32).u32(v));
        }
        fits.add(() -> w.u8(Codes.UINT64).u64(v));
    }

    private static void addSigned(RawWriter w, long v, List<Runnable> fits) {
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            fits.add(() -> w.u8(Codes.INT8).u8((int) v));
        }
        if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            fits.add(() -> w.u8(Codes.INT16).u16((int) v));
        }
        if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
            fits.add(() -> w.u8(Codes.INT32).u32(v));
        }
        fits.add(() -> w.u8(Codes.INT64).u64(v));
    }

    private static void string(RawWriter w, Random r, Options options) {
        byte[] utf8;
        if (options.hasInvalidUtf8() && r.nextInt(15) == 0) {
            utf8 = randomBytes(r, 1 + r.nextInt(8));
            utf8[0] = (byte) (0x80 | utf8[0]);
        } else {
            int length = r.nextInt(4) == 0 ? r.nextInt(options.maxStringLength() + 1) : r.nextInt(40);
            utf8 = javaString(r, length).getBytes(StandardCharsets.UTF_8);
        }
        w.str(utf8, lengthFamily(r, utf8.length, true));
    }

    private static void binary(RawWriter w, Random r, Options options) {
        byte[] data = randomBytes(r, r.nextInt(4) == 0 ? r.nextInt(options.maxStringLength() + 1) : r.nextInt(20));
        w.bin(data, lengthFamily(r, data.length, false));
    }

    private static void extension(RawWriter w, Random r, Options options) {
        boolean isTimestamp = r.nextInt(3) == 0;
        byte type = isTimestamp ? TIMESTAMP : (byte) r.nextInt(128);
        int length;
        if (isTimestamp) {
            length = options.hasBadTimestamps() && r.nextInt(5) == 0
                    ? FIXEXT_LENGTHS[r.nextInt(FIXEXT_LENGTHS.length)] + 1
                    : TIMESTAMP_LENGTHS[r.nextInt(TIMESTAMP_LENGTHS.length)];
        } else {
            length = r.nextInt(3) == 0 ? FIXEXT_LENGTHS[r.nextInt(FIXEXT_LENGTHS.length)] : r.nextInt(40);
        }
        byte[] data = isTimestamp ? timestampPayload(r, length) : randomBytes(r, length);
        boolean canFix = length == 1 || length == 2 || length == 4 || length == 8 || length == 16;
        w.ext(type, data, canFix && r.nextBoolean(), lengthFamily(r, length, false));
    }

    /** A payload whose seconds stay inside {@code Instant}'s range when it is 12 bytes. */
    private static byte[] timestampPayload(Random r, int length) {
        byte[] data = randomBytes(r, length);
        if (length == 12) {
            data[4] = 0;
            data[5] = (byte) (data[5] & 0x0f);
        }
        return data;
    }

    static byte[] randomBytes(Random r, int length) {
        byte[] data = new byte[length];
        r.nextBytes(data);
        return data;
    }

    /** @return 0 (fix form), 8, 16 or 32: any header family that can carry {@code length} */
    private static int lengthFamily(Random r, int length, boolean hasFixForm) {
        List<Integer> fits = new ArrayList<>();
        if (hasFixForm && length < Codes.FIXSTR_LIMIT) {
            fits.add(0);
        }
        if (length < Codes.U8_LIMIT) {
            fits.add(Byte.SIZE);
        }
        if (length < Codes.U16_LIMIT) {
            fits.add(Short.SIZE);
        }
        fits.add(Integer.SIZE);
        return fits.get(r.nextInt(fits.size()));
    }

    /** @return 0 (fix form), 16 or 32: any container header family that can carry {@code count} */
    private static int countFamily(Random r, int count) {
        List<Integer> fits = new ArrayList<>();
        if (count < Codes.FIXMAP_LIMIT) {
            fits.add(0);
        }
        fits.add(Short.SIZE);
        fits.add(Integer.SIZE);
        return fits.get(r.nextInt(fits.size()));
    }
}
