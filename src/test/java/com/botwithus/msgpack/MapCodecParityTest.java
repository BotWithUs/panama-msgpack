package com.botwithus.msgpack;

import com.botwithus.msgpack.adapters.MapCodecAdapter;
import com.botwithus.msgpack.adapters.MapCodecAdapter.AdapterException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The consumer codec rebuilt on this library ({@link MapCodecAdapter}) against
 * the legacy msgpack-core codec ({@link LegacyCodec}): the same bytes out of
 * every encode, and the same value or the same refusal out of every decode.
 */
class MapCodecParityTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final int ENCODE_CASES = 3_000;
    private static final int DECODE_CASES = 5_000;
    private static final int TRUNCATION_FRAMES = 300;
    private static final int CORRUPTION_CASES = 20_000;
    private static final int VERY_DEEP = 200_000;

    /** What one decoder made of one frame: a canonical value, or that it refused. */
    private record Outcome(boolean isRejected, String canonical) {

        static Outcome of(Supplier<Map<String, Object>> decode) {
            try {
                return new Outcome(false, Canon.ofJava(decode.get()));
            } catch (AdapterException | LegacyCodec.LegacyException e) {
                return new Outcome(true, e.getMessage());
            } catch (OutOfMemoryError e) {
                // Only the legacy codec gets here: msgpack-core allocates a str/bin
                // payload, or a container's slots, from the length prefix before it
                // checks the input holds that many bytes. It is a refusal, just a
                // fatal one, and the reason the port bounds-checks first.
                return new Outcome(true, "legacy OutOfMemoryError: " + e.getMessage());
            }
        }
    }

    private static void assertSameOutcome(byte[] frame) {
        Outcome ours = Outcome.of(() -> MapCodecAdapter.decode(frame));
        Outcome legacy = Outcome.of(() -> LegacyCodec.decode(frame));
        assertEquals(legacy.isRejected(), ours.isRejected(), () -> "accept/reject differs for " + HEX.formatHex(frame)
                + "\n legacy: " + legacy + "\n ours:   " + ours);
        if (!ours.isRejected()) {
            assertEquals(legacy.canonical(), ours.canonical(), () -> "value differs for " + HEX.formatHex(frame));
        }
    }

    @Test
    void encode_isByteIdenticalToLegacy() {
        Seeds.run("encode", ENCODE_CASES, r -> {
            Map<String, Object> map = Gen.javaMap(r, 3);
            assertEquals(HEX.formatHex(LegacyCodec.encode(map)), HEX.formatHex(MapCodecAdapter.encode(map)),
                    () -> Canon.ofJava(map));
        });
    }

    @Test
    void encode_failsANonStringKeyWithTheLegacyExceptionTypes() {
        Map<String, Object> intKey = new LinkedHashMap<>();
        intKey.put("m", Map.of(1, "x"));
        assertThrows(ClassCastException.class, () -> LegacyCodec.encode(intKey));
        assertThrows(ClassCastException.class, () -> MapCodecAdapter.encode(intKey));
        Map<Object, Object> nullKeyMap = new LinkedHashMap<>();
        nullKeyMap.put(null, "x");
        Map<String, Object> nullKey = new LinkedHashMap<>();
        nullKey.put("m", nullKeyMap);
        assertThrows(NullPointerException.class, () -> LegacyCodec.encode(nullKey));
        assertThrows(NullPointerException.class, () -> MapCodecAdapter.encode(nullKey));
    }

    @Test
    void decode_randomFramesInEveryEncoding_matchLegacy() {
        Seeds.run("decode", DECODE_CASES, r -> assertSameOutcome(Gen.frame(r, Gen.Options.everything())));
    }

    @Test
    void decode_everyTruncation_isRefusedByBoth() {
        Seeds.run("truncation", TRUNCATION_FRAMES, r -> {
            byte[] frame = Gen.frame(r, Gen.Options.wellFormed());
            for (int cut = 0; cut < frame.length; cut++) {
                byte[] prefix = Arrays.copyOf(frame, cut);
                assertThrows(AdapterException.class, () -> MapCodecAdapter.decode(prefix), () -> "cut at " + prefix.length);
                assertThrows(LegacyCodec.LegacyException.class, () -> LegacyCodec.decode(prefix));
            }
        });
    }

    /**
     * One random byte changed. Where the change makes a length or count larger
     * than the input, this library refuses before allocating; the legacy codec
     * allocates first (up to {@code new Value[2^31-1]}) and may die of an
     * OutOfMemoryError, so only our refusal is asserted there.
     */
    @Test
    void decode_singleByteCorruption_agreesWithLegacyOrIsAnEarlierRefusal() {
        Seeds.run("corruption", CORRUPTION_CASES, r -> {
            byte[] frame = Gen.frame(r, Gen.Options.everything());
            if (frame.length == 0) {
                return;
            }
            frame[r.nextInt(frame.length)] = (byte) r.nextInt();
            Outcome ours = Outcome.of(() -> MapCodecAdapter.decode(frame));
            if (ours.isRejected() && isOversizeRefusal(ours.canonical())) {
                return;
            }
            assertSameOutcome(frame);
        });
    }

    private static boolean isOversizeRefusal(String message) {
        return message.contains("exceeds the") || message.contains("cannot fit");
    }

    @Test
    void decode_depthBoundary_matchesLegacyExactly_forMapsArraysAndMixes() {
        for (int depth = 1; depth <= 70; depth++) {
            assertSameOutcome(nested(depth, "map"));
            assertSameOutcome(nested(depth, "array"));
            assertSameOutcome(nested(depth, "mixed"));
        }
    }

    @Test
    void decode_veryDeepInput_isANestingRefusal_notAStackOverflow() {
        AdapterException e = assertThrows(AdapterException.class, () -> MapCodecAdapter.decode(nested(VERY_DEEP, "map")));
        assertTrue(e.getMessage().contains("nesting"), e.getMessage());
        assertInstanceOf(MsgPackException.class, e.getCause(), "the cause is the library's own refusal");
    }

    @Test
    void decode_nonMinimalEncodings_readAsTheirValues() {
        byte[] frame = new RawWriter().mapHeader(6, Short.SIZE)
                .str(bytes("a"), Integer.SIZE).u8(Codes.INT32).u32(5)
                .str(bytes("b"), Byte.SIZE).u8(Codes.UINT64).u64(7)
                .str(bytes("c"), 0).str(bytes("hi"), Integer.SIZE)
                .str(bytes("d"), 0).u8(Codes.INT64).u64(Integer.MAX_VALUE + 1L)
                .str(bytes("e"), 0).f32(1.5f)
                .str(bytes("f"), 0).arrayHeader(1, Integer.SIZE).u8(Codes.INT16).u16(-3)
                .toByteArray();
        Map<String, Object> decoded = MapCodecAdapter.decode(frame);
        assertEquals(5L, decoded.get("a"));
        assertEquals(7L, decoded.get("b"));
        assertEquals("hi", decoded.get("c"));
        assertEquals(Integer.MAX_VALUE + 1L, decoded.get("d"));
        assertEquals(1.5d, decoded.get("e"));
        assertEquals(List.of(-3L), decoded.get("f"));
        assertSameOutcome(frame);
    }

    @Test
    void decode_refusalsTheLegacyCodecMade_areStillRefusals() {
        byte[][] refused = {
            new RawWriter().mapHeader(1, 0).str(bytes("u"), 0).u8(Codes.UINT64).u64(-1L).toByteArray(),
            new RawWriter().mapHeader(1, 0).u8(1).u8(Codes.NIL).toByteArray(),
            new RawWriter().arrayHeader(0, 0).toByteArray(),
            new RawWriter().mapHeader(1, 0).str(bytes("s"), 0).str(new byte[] {(byte) 0xc3}, 0).toByteArray(),
            new RawWriter().mapHeader(1, 0).str(bytes("t"), 0).ext((byte) -1, new byte[3], false, Byte.SIZE)
                    .toByteArray(),
            new RawWriter().mapHeader(1, 0).str(bytes("x"), 0).u8(Codes.NEVER_USED).toByteArray(),
            new byte[0],
        };
        for (byte[] frame : refused) {
            assertThrows(LegacyCodec.LegacyException.class, () -> LegacyCodec.decode(frame), HEX.formatHex(frame));
            assertThrows(AdapterException.class, () -> MapCodecAdapter.decode(frame), HEX.formatHex(frame));
        }
        assertThrows(AdapterException.class, () -> MapCodecAdapter.decode(null));
        assertThrows(LegacyCodec.LegacyException.class, () -> LegacyCodec.decode(null));
    }

    /**
     * The legacy decoder's {@code isInIntRange() ? asInt() : asLong()} is a
     * numeric ternary, so the int arm widens to {@code long} before boxing:
     * every integer it has ever returned is a {@code Long}. Pinned here so a
     * port cannot "fix" it into an {@code Integer} and break every caller that
     * casts or pattern-matches on {@code Long}.
     */
    @Test
    void decode_everyIntegerIsALong_evenInTheIntRange() {
        byte[] frame = new RawWriter().mapHeader(1, 0).str(bytes("i"), 0).u8(5).toByteArray();
        assertEquals(Long.class, LegacyCodec.decode(frame).get("i").getClass());
        assertEquals(Long.class, MapCodecAdapter.decode(frame).get("i").getClass());
    }

    @Test
    void decode_trailingBytesAreIgnored_asTheyWere() {
        byte[] frame = new RawWriter().mapHeader(0, 0).u8(Codes.NEVER_USED).u8(Codes.NIL).toByteArray();
        assertNotNull(MapCodecAdapter.decode(frame));
        assertNotNull(LegacyCodec.decode(frame));
    }

    @Test
    void decode_extensionsBecomeTheLegacyStrings() {
        byte[] frame = new RawWriter().mapHeader(2, 0)
                .str(bytes("e"), 0).ext((byte) 5, new byte[] {0, 15, (byte) 0xff, (byte) 0x80}, true, 0)
                .str(bytes("t"), 0).ext((byte) -1, new byte[] {0, 0, 0, 1}, true, 0)
                .toByteArray();
        Map<String, Object> decoded = MapCodecAdapter.decode(frame);
        assertEquals("(5,0x0f-1-80)", decoded.get("e"));
        assertEquals("1970-01-01T00:00:01Z", decoded.get("t"));
        assertSameOutcome(frame);
    }

    @Test
    void decode_aDuplicateKeyKeepsTheFirstPositionAndTheLastValue_asTheLegacyMapDid() {
        byte[] frame = new RawWriter().mapHeader(3, 0).str(bytes("a"), 0).u8(1).str(bytes("b"), 0).u8(2)
                .str(bytes("a"), 0).u8(3).toByteArray();
        assertEquals(List.of("a", "b"), List.copyOf(MapCodecAdapter.decode(frame).keySet()));
        assertEquals(3L, MapCodecAdapter.decode(frame).get("a"));
        assertSameOutcome(frame);
        assertFalse(MapCodecAdapter.decode(frame).isEmpty());
    }

    /** @return a map root holding {@code depth} levels of {@code shape} around a nil */
    private static byte[] nested(int depth, String shape) {
        RawWriter w = new RawWriter().mapHeader(1, 0).str(bytes("k"), 0);
        for (int i = 0; i < depth; i++) {
            boolean isMap = switch (shape) {
                case "map" -> true;
                case "array" -> false;
                case "mixed" -> i % 2 == 0;
                default -> fail("unknown shape " + shape);
            };
            if (isMap) {
                w.mapHeader(1, 0).str(bytes("k"), 0);
            } else {
                w.arrayHeader(1, 0);
            }
        }
        return w.u8(Codes.NIL).toByteArray();
    }

    private static byte[] bytes(String ascii) {
        return ascii.getBytes(StandardCharsets.US_ASCII);
    }
}
