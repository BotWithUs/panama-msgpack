package com.botwithus.msgpack;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The packer writes exactly the bytes msgpack-core 0.9.8 writes for the same
 * call: the smallest encoding of every integer, length and count, with the same
 * signed/unsigned choice and the same UTF-8 for odd strings.
 */
class PackerParityTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final int RANDOM_CASES = 20_000;
    private static final int TREE_CASES = 2_000;
    private static final int[] HEADER_SIZES = {0, 1, 15, 16, 31, 32, 255, 256, 65535, 65536, Integer.MAX_VALUE};
    private static final int[] EXT_LENGTHS = {0, 1, 2, 3, 4, 5, 8, 9, 16, 17, 31, 32, 255, 256, 65535, 65536};

    private static void assertSameBytes(byte[] expected, byte[] actual, String what) {
        assertEquals(HEX.formatHex(expected), HEX.formatHex(actual), what);
    }

    @Test
    void integerBoundaries_matchMsgpackCore() {
        for (long boundary : Gen.INTEGER_BOUNDARIES) {
            for (int offset = -1; offset <= 1; offset++) {
                long value = boundary + offset;
                assertSameBytes(CoreOracle.pack(p -> p.packLong(value)), new MsgPacker().packLong(value).toByteArray(),
                        "packLong(" + value + ")");
                if (value == (int) value) {
                    assertSameBytes(CoreOracle.pack(p -> p.packInt((int) value)),
                            new MsgPacker().packInt((int) value).toByteArray(), "packInt(" + value + ")");
                }
            }
        }
    }

    @Test
    void randomIntegersAndFloats_matchMsgpackCore() {
        Seeds.run("random scalars", RANDOM_CASES, r -> {
            long l = r.nextLong() >> r.nextInt(Long.SIZE);
            float f = Gen.floatValue(r);
            double d = Gen.doubleValue(r);
            long bits = r.nextLong();
            assertSameBytes(CoreOracle.pack(p -> p.packLong(l)), new MsgPacker().packLong(l).toByteArray(), "long " + l);
            assertSameBytes(CoreOracle.pack(p -> p.packFloat(f)), new MsgPacker().packFloat(f).toByteArray(), "float");
            assertSameBytes(CoreOracle.pack(p -> p.packDouble(d)), new MsgPacker().packDouble(d).toByteArray(), "double");
            BigInteger unsigned = new BigInteger(Long.toUnsignedString(bits));
            assertSameBytes(CoreOracle.pack(p -> p.packBigInteger(unsigned)),
                    new MsgPacker().packUnsignedLong(bits).toByteArray(), "unsigned " + unsigned);
        });
    }

    @Test
    void stringsAtEveryLengthBoundary_matchMsgpackCore() {
        Random r = new Random(Seeds.baseSeed());
        for (int length : Gen.LENGTH_BOUNDARIES) {
            for (int extra = -1; extra <= 1; extra++) {
                if (length + extra < 0) {
                    continue;
                }
                String s = Gen.javaString(r, length + extra);
                assertSameBytes(CoreOracle.pack(p -> p.packString(s)), new MsgPacker().packString(s).toByteArray(),
                        "string of " + s.length() + " units");
            }
        }
    }

    @Test
    void loneSurrogates_encodeAsMsgpackCoreDoes_onBothOfItsPaths() {
        // Under 512 units msgpack-core uses String.getBytes; at 512 and over, a
        // CharsetEncoder set to REPLACE. Both must agree with this packer.
        for (int length : new int[] {1, 511, 512, 4000}) {
            String s = "\uD800".repeat(length / 2) + "x\uDFFF".repeat(length - length / 2);
            assertSameBytes(CoreOracle.pack(p -> p.packString(s)), new MsgPacker().packString(s).toByteArray(),
                    "lone surrogates, " + s.length() + " units");
        }
    }

    @Test
    void headers_matchMsgpackCore() {
        for (int size : HEADER_SIZES) {
            assertSameBytes(CoreOracle.pack(p -> p.packRawStringHeader(size)),
                    new MsgPacker().packStringHeader(size).toByteArray(), "str header " + size);
            assertSameBytes(CoreOracle.pack(p -> p.packBinaryHeader(size)),
                    new MsgPacker().packBinaryHeader(size).toByteArray(), "bin header " + size);
            assertSameBytes(CoreOracle.pack(p -> p.packArrayHeader(size)),
                    new MsgPacker().packArrayHeader(size).toByteArray(), "array header " + size);
            assertSameBytes(CoreOracle.pack(p -> p.packMapHeader(size)),
                    new MsgPacker().packMapHeader(size).toByteArray(), "map header " + size);
        }
        for (int length : EXT_LENGTHS) {
            assertSameBytes(CoreOracle.pack(p -> p.packExtensionTypeHeader((byte) 7, length)),
                    new MsgPacker().packExtensionHeader((byte) 7, length).toByteArray(), "ext header " + length);
        }
        assertSameBytes(CoreOracle.pack(p -> p.packNil().packBoolean(true).packBoolean(false)),
                new MsgPacker().packNil().packBoolean(true).packBoolean(false).toByteArray(), "nil and booleans");
    }

    @Test
    void randomValueTrees_packAsMsgpackCorePacksThem() {
        Seeds.run("value trees", TREE_CASES, r -> {
            MsgValue tree = randomTree(r, 3);
            assertSameBytes(CoreOracle.pack(p -> p.packValue(CoreOracle.toCore(tree))), MsgPack.encode(tree),
                    Canon.ofMsg(tree));
        });
    }

    /** A tree of every type msgpack-core packs back unchanged: floats are float64, no timestamps. */
    static MsgValue randomTree(Random r, int depth) {
        int kinds = depth > 0 ? 9 : 7;
        return switch (r.nextInt(kinds)) {
            case 0 -> MsgValue.nil();
            case 1 -> MsgValue.of(r.nextBoolean());
            case 2 -> r.nextInt(8) == 0 ? MsgInteger.ofUnsigned(r.nextLong()) : MsgValue.of(Gen.integer(r));
            case 3 -> MsgValue.of(Gen.doubleValue(r));
            case 4 -> MsgValue.of(Gen.javaString(r, r.nextInt(40)));
            case 5 -> MsgValue.of(Gen.randomBytes(r, r.nextInt(300)));
            case 6 -> MsgExtension.of((byte) r.nextInt(100), Gen.randomBytes(r, r.nextInt(20)));
            case 7 -> {
                List<MsgValue> items = new ArrayList<>();
                int size = r.nextInt(4) == 0 ? 17 : r.nextInt(4);
                for (int i = 0; i < size; i++) {
                    items.add(randomTree(r, depth - 1));
                }
                yield MsgValue.of(items);
            }
            default -> {
                MsgMap.Builder map = MsgMap.builder();
                int size = r.nextInt(4) == 0 ? 17 : r.nextInt(4);
                for (int i = 0; i < size; i++) {
                    map.put(randomTree(r, 0), randomTree(r, depth - 1));
                }
                yield map.build();
            }
        };
    }
}
