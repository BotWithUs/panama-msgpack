package com.botwithus.msgpack;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The unpacker reads every encoding msgpack-core reads, to the same values,
 * and refuses bad input with {@link MsgPackException} and nothing else.
 */
class UnpackerTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final int TREE_CASES = 5_000;
    private static final int TRUNCATION_FRAMES = 300;
    private static final int FUZZ_CASES = 50_000;
    private static final int VERY_DEEP = 200_000;
    private static final long[] OVERSIZE_LENGTHS = {0xffff_ffffL, 0x7fff_ffffL, 0x8000_0000L, 1000};

    /** Runs {@code read} and fails unless it returns or throws exactly {@link MsgPackException}. */
    private static void assertOnlyMsgPackException(byte[] frame, Consumer<byte[]> read) {
        try {
            read.accept(frame);
        } catch (MsgPackException e) {
            assertEquals(MsgPackException.class, e.getClass(), "exactly MsgPackException");
        } catch (Throwable t) {
            fail("threw " + t + " for " + HEX.formatHex(frame), t);
        }
    }

    @Test
    void valueTree_matchesMsgpackCore_andRoundTrips() {
        Gen.Options options = new Gen.Options(false, true, true, true, 4, 300);
        Seeds.run("trees", TREE_CASES, r -> {
            byte[] frame = Gen.frame(r, options);
            MsgValue ours = MsgPack.decode(frame);
            assertEquals(Canon.ofCore(CoreOracle.unpack(frame)), Canon.ofMsg(ours), () -> HEX.formatHex(frame));
            assertEquals(ours, MsgPack.decode(MsgPack.encode(ours)), "decode(encode(v)) == v");
        });
    }

    @Test
    void skipValue_stopsWhereUnpackValueStops() {
        Seeds.run("skip", TREE_CASES, r -> {
            byte[] frame = Gen.frame(r, Gen.Options.everything());
            MsgUnpacker read = new MsgUnpacker(frame);
            read.unpackValue(MsgDecodeRules.DEFAULT_MAX_DEPTH);
            MsgUnpacker skip = new MsgUnpacker(frame);
            skip.skipValue();
            assertEquals(read.position(), skip.position());
        });
    }

    @Test
    void anOffHeapSegment_decodesTheSameAsTheArray() {
        Seeds.run("native segment", 500, r -> {
            byte[] frame = Gen.frame(r, Gen.Options.wellFormed());
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment segment = arena.allocate(frame.length);
                MemorySegment.copy(frame, 0, segment, ValueLayout.JAVA_BYTE, 0, frame.length);
                assertEquals(MsgPack.decode(frame), MsgPack.decode(segment, MsgDecodeRules.strict()));
            }
        });
    }

    @Test
    void readPayloadSegment_isAViewWithNoCopy() {
        byte[] frame = new RawWriter().bin(new byte[] {1, 2, 3}, Byte.SIZE).toByteArray();
        MsgUnpacker unpacker = new MsgUnpacker(frame);
        MemorySegment view = unpacker.readPayloadSegment(unpacker.unpackBinaryHeader());
        frame[2] = 9;
        assertArrayEquals(new byte[] {9, 2, 3}, view.toArray(ValueLayout.JAVA_BYTE));
        assertFalse(unpacker.hasNext());
    }

    @Test
    void everyTruncation_isAMsgPackException() {
        Seeds.run("truncation", TRUNCATION_FRAMES, r -> {
            byte[] frame = Gen.frame(r, Gen.Options.everything());
            for (int cut = 0; cut < frame.length; cut++) {
                byte[] prefix = Arrays.copyOf(frame, cut);
                assertThrows(MsgPackException.class, () -> MsgPack.decode(prefix), () -> "cut at " + prefix.length);
                assertThrows(MsgPackException.class, () -> new MsgUnpacker(prefix).skipValue());
            }
        });
    }

    @Test
    void randomCorruption_neverThrowsAnythingButMsgPackException() {
        Seeds.run("corruption", FUZZ_CASES, r -> {
            byte[] frame = Gen.frame(r, Gen.Options.everything());
            int flips = 1 + r.nextInt(3);
            for (int i = 0; i < flips && frame.length > 0; i++) {
                frame[r.nextInt(frame.length)] = (byte) r.nextInt();
            }
            assertOnlyMsgPackException(frame, MsgPack::decode);
            assertOnlyMsgPackException(frame, f -> new MsgUnpacker(f).skipValue());
            assertOnlyMsgPackException(frame, f -> MsgTimestampsProbe.readAll(f));
        });
    }

    @Test
    void pureNoise_neverThrowsAnythingButMsgPackException() {
        Seeds.run("noise", FUZZ_CASES, r -> {
            byte[] noise = Gen.randomBytes(r, r.nextInt(64));
            assertOnlyMsgPackException(noise, MsgPack::decode);
            assertOnlyMsgPackException(noise, f -> new MsgUnpacker(f).skipValue());
        });
    }

    @Test
    void anOversizeLengthOrCount_isRefusedBeforeAnythingIsAllocated() {
        for (long length : OVERSIZE_LENGTHS) {
            byte[][] frames = {
                new RawWriter().u8(Codes.STR32).u32(length).u8('a').toByteArray(),
                new RawWriter().u8(Codes.BIN32).u32(length).u8(1).toByteArray(),
                new RawWriter().u8(Codes.EXT32).u32(length).u8(1).u8(1).toByteArray(),
                new RawWriter().u8(Codes.ARRAY32).u32(length).u8(Codes.NIL).toByteArray(),
                new RawWriter().u8(Codes.MAP32).u32(length).u8(Codes.NIL).u8(Codes.NIL).toByteArray(),
            };
            for (byte[] frame : frames) {
                MsgPackException e = assertThrows(MsgPackException.class, () -> MsgPack.decode(frame),
                        HEX.formatHex(frame));
                assertTrue(e.getMessage().contains("bytes left"), e.getMessage());
            }
        }
    }

    @Test
    void nestingPastTheLimit_isRefusedBeforeDescending_atAnyDepth() {
        assertEquals(MsgType.ARRAY, MsgPack.decode(nestedArrays(5), MsgDecodeRules.strict().withMaxDepth(5)).type());
        MsgPackException atLimit = assertThrows(MsgPackException.class,
                () -> MsgPack.decode(nestedArrays(6), MsgDecodeRules.strict().withMaxDepth(5)));
        assertTrue(atLimit.getMessage().contains("nesting exceeds 5"), atLimit.getMessage());
        MsgPackException deep = assertThrows(MsgPackException.class, () -> MsgPack.decode(nestedArrays(VERY_DEEP)));
        assertTrue(deep.getMessage().contains("nesting"), deep.getMessage());
        new MsgUnpacker(nestedArrays(VERY_DEEP)).skipValue();
    }

    @Test
    void uint64AboveLongMax_isAValueButNotALong() {
        byte[] frame = new RawWriter().u8(Codes.UINT64).u64(-2L).toByteArray();
        MsgInteger value = new MsgUnpacker(frame).unpackInteger();
        assertEquals("18446744073709551614", value.toString());
        assertTrue(value.longValue().isEmpty());
        assertThrows(MsgPackException.class, () -> new MsgUnpacker(frame).unpackLong());
        assertThrows(MsgPackException.class, () -> new MsgUnpacker(frame).unpackInt());
    }

    @Test
    void nonMinimalAndWideEncodings_readAsTheirValues() {
        assertEquals(MsgInteger.of(5), MsgPack.decode(new RawWriter().u8(Codes.INT32).u32(5).toByteArray()));
        assertEquals(MsgInteger.of(1), MsgPack.decode(new RawWriter().u8(Codes.UINT64).u64(1).toByteArray()));
        assertEquals(MsgInteger.of(-1), MsgPack.decode(new RawWriter().u8(Codes.INT64).u64(-1).toByteArray()));
        assertEquals(MsgValue.of("a"), MsgPack.decode(new RawWriter().str(utf8("a"), Integer.SIZE).toByteArray()));
        assertEquals(MsgValue.of("a"), MsgPack.decode(new RawWriter().str(utf8("a"), Short.SIZE).toByteArray()));
        for (int family : new int[] {Byte.SIZE, Short.SIZE, Integer.SIZE}) {
            assertEquals(MsgValue.of(new byte[] {7}),
                    MsgPack.decode(new RawWriter().bin(new byte[] {7}, family).toByteArray()));
            assertEquals(MsgExtension.of((byte) 3, new byte[] {7}),
                    MsgPack.decode(new RawWriter().ext((byte) 3, new byte[] {7}, false, family).toByteArray()));
        }
        assertEquals(MsgExtension.of((byte) 3, new byte[16]),
                MsgPack.decode(new RawWriter().ext((byte) 3, new byte[16], true, 0).toByteArray()));
        assertEquals(new MsgArray(List.of()),
                MsgPack.decode(new RawWriter().arrayHeader(0, Integer.SIZE).toByteArray()));
        assertEquals(MsgMap.empty(), MsgPack.decode(new RawWriter().mapHeader(0, Short.SIZE).toByteArray()));
        assertEquals(MsgFloat.ofSingle(1.5f), MsgPack.decode(new RawWriter().f32(1.5f).toByteArray()));
    }

    @Test
    void theNeverUsedByte_isRefused() {
        assertThrows(MsgPackException.class, () -> MsgPack.decode(new byte[] {(byte) Codes.NEVER_USED}));
        assertThrows(MsgPackException.class, () -> new MsgUnpacker(new byte[] {(byte) Codes.NEVER_USED}).skipValue());
    }

    @Test
    void aReadOfTheWrongType_leavesTheCursorWhereItWas() {
        MsgUnpacker unpacker = new MsgUnpacker(new RawWriter().str(utf8("x"), 0).toByteArray());
        assertThrows(MsgPackException.class, unpacker::unpackLong);
        assertEquals(0, unpacker.position());
        assertEquals("x", unpacker.unpackString());
    }

    @Test
    void rules_trailingDataAndMapRoot() {
        byte[] twoValues = new RawWriter().u8(1).u8(2).toByteArray();
        assertThrows(MsgPackException.class, () -> MsgPack.decode(twoValues));
        assertEquals(MsgInteger.of(1), MsgPack.decode(twoValues, MsgDecodeRules.strict().allowingTrailingData()));
        MsgDecodeRules mapRoot = MsgDecodeRules.strict().allowingTrailingData().requiringMapRoot();
        assertThrows(MsgPackException.class, () -> MsgPack.decode(twoValues, mapRoot));
        assertThrows(MsgPackException.class, () -> MsgPack.decodeMap(twoValues, MsgDecodeRules.strict()));
        assertEquals(MsgMap.empty(), MsgPack.decodeMap(new byte[] {(byte) Codes.FIXMAP_PREFIX}, MsgDecodeRules.strict()));
    }

    @Test
    void invalidUtf8_isKeptAsBytes_andRefusedOnlyWhenDecoded() {
        byte[] frame = new RawWriter().str(new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, 0).toByteArray();
        MsgString value = new MsgUnpacker(frame).unpackStringValue();
        assertEquals(3, value.byteLength());
        assertThrows(MsgPackException.class, value::asString);
        assertThrows(MsgPackException.class, () -> new MsgUnpacker(frame).unpackString());
        assertArrayEquals(frame, MsgPack.encode(value), "packs back to the same bytes");
    }

    @Test
    void theStrictDecoder_agreesWithTheJdkOnValidUtf8() {
        Random r = new Random(Seeds.baseSeed());
        for (int i = 0; i < 2_000; i++) {
            String s = Gen.javaString(r, r.nextInt(30)).replace('\uD800', 'x').replace('\uDFFF', 'y');
            assertEquals(new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
                    new MsgUnpacker(new MsgPacker().packString(s).toByteArray()).unpackString());
        }
    }

    private static byte[] nestedArrays(int depth) {
        RawWriter w = new RawWriter();
        for (int i = 0; i < depth; i++) {
            w.arrayHeader(1, 0);
        }
        return w.u8(Codes.NIL).toByteArray();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Reads every value with the typed calls, decoding timestamps, so the fuzz reaches those paths too. */
    private static final class MsgTimestampsProbe {

        private MsgTimestampsProbe() {
        }

        static void readAll(byte[] frame) {
            MsgUnpacker unpacker = new MsgUnpacker(frame);
            long budget = frame.length + 1L;
            while (unpacker.hasNext() && budget-- > 0) {
                switch (unpacker.nextType()) {
                    case EXTENSION -> {
                        MsgExtension ext = unpacker.unpackExtension();
                        if (MsgTimestamps.isTimestamp(ext)) {
                            MsgTimestamps.decode(ext);
                        }
                    }
                    case STRING -> unpacker.unpackString();
                    case INTEGER -> unpacker.unpackLong();
                    case ARRAY -> unpacker.unpackArrayHeader();
                    case MAP -> unpacker.unpackMapHeader();
                    default -> unpacker.skipValue();
                }
            }
        }
    }
}
