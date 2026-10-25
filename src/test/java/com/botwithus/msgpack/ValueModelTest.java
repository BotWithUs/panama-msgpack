package com.botwithus.msgpack;

import org.junit.jupiter.api.Test;
import org.msgpack.core.ExtensionTypeHeader;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The value model is immutable, compares by value and reads timestamps as msgpack-core does. */
class ValueModelTest {

    private static final int TIMESTAMP_CASES = 5_000;
    private static final int[] TIMESTAMP_LENGTHS = {4, 8, 12};

    @Test
    void integers_haveOneRepresentationPerNumber() {
        assertEquals(MsgInteger.of(7), MsgInteger.ofUnsigned(7));
        assertEquals(MsgInteger.of(7), MsgPack.decode(new RawWriter().u8(Codes.UINT64).u64(7).toByteArray()));
        assertNotEquals(MsgInteger.of(-1), MsgInteger.ofUnsigned(-1));
        assertEquals(new BigInteger("18446744073709551615"), MsgInteger.ofUnsigned(-1).toBigInteger());
        assertEquals(MsgInteger.ofUnsigned(-1), MsgInteger.of(new BigInteger("18446744073709551615")));
        assertEquals(MsgInteger.of(Long.MIN_VALUE), MsgInteger.of(BigInteger.valueOf(Long.MIN_VALUE)));
        assertThrows(IllegalArgumentException.class, () -> MsgInteger.of(BigInteger.ONE.shiftLeft(64)));
        assertThrows(IllegalArgumentException.class,
                () -> MsgInteger.of(BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE)));
        assertTrue(MsgInteger.of(Integer.MIN_VALUE).isInIntRange());
        assertFalse(MsgInteger.of(Integer.MAX_VALUE + 1L).isInIntRange());
    }

    @Test
    void mapBuilder_replacesAValueInPlace_andGetFindsTheFirstMatch() {
        MsgMap map = MsgMap.builder().put("a", MsgValue.of(1)).put("b", MsgValue.of(2)).put("a", MsgValue.of(3))
                .build();
        assertEquals(2, map.size());
        assertEquals(MsgValue.of("a"), map.entries().getFirst().key());
        assertEquals(Optional.of(MsgValue.of(3)), map.get("a"));
        assertEquals(Optional.empty(), map.get("z"));
        MsgMap duplicates = MsgPack.decodeMap(new RawWriter().mapHeader(2, 0).str(new byte[] {'a'}, 0).u8(1)
                .str(new byte[] {'a'}, 0).u8(2).toByteArray(), MsgDecodeRules.strict());
        assertEquals(2, duplicates.size(), "a decoded map keeps duplicates");
        assertEquals(Optional.of(MsgValue.of(1)), duplicates.get("a"));
    }

    @Test
    void optionalAccessors_readTheMatchingTypeOnly() {
        assertEquals(Optional.of("x"), MsgValue.of("x").stringValue());
        assertEquals(Optional.empty(), MsgValue.of(1).stringValue());
        assertEquals(1L, MsgValue.of(1).longValue().orElseThrow());
        assertTrue(MsgInteger.ofUnsigned(-1).longValue().isEmpty());
        assertEquals(Optional.of(true), MsgValue.of(true).booleanValue());
        assertTrue(MsgValue.nil().isNil());
        assertArrayEquals(new byte[] {1}, MsgValue.of(new byte[] {1}).binaryValue().orElseThrow());
        assertTrue(MsgMap.empty().mapValue().isPresent());
        assertTrue(MsgValue.of(List.of()).arrayValue().isPresent());
    }

    @Test
    void values_areImmutable() {
        byte[] data = {1, 2};
        MsgValue binary = MsgValue.of(data);
        data[0] = 9;
        assertArrayEquals(new byte[] {1, 2}, binary.binaryValue().orElseThrow());
        binary.binaryValue().orElseThrow()[0] = 9;
        assertArrayEquals(new byte[] {1, 2}, binary.binaryValue().orElseThrow());
        List<MsgValue> items = new ArrayList<>(List.of(MsgValue.of(1)));
        MsgArray array = new MsgArray(items);
        items.add(MsgValue.of(2));
        assertEquals(1, array.size());
        assertThrows(UnsupportedOperationException.class, () -> array.values().add(MsgValue.nil()));
    }

    @Test
    void timestamps_readAsMsgpackCoreReadsThem() {
        Seeds.run("timestamps", TIMESTAMP_CASES, r -> {
            int length = TIMESTAMP_LENGTHS[r.nextInt(TIMESTAMP_LENGTHS.length)];
            byte[] payload = Gen.randomBytes(r, length);
            if (length == 12) {
                payload[4] = 0;
                payload[5] = (byte) (payload[5] & 0x0f);
            }
            byte[] frame = new RawWriter().ext(MsgTimestamps.TIMESTAMP_TYPE, payload, length != 12, Byte.SIZE)
                    .toByteArray();
            assertEquals(coreTimestamp(frame), MsgTimestamps.decode(new MsgUnpacker(frame).unpackExtension()));
        });
    }

    @Test
    void aTimestampOfTheWrongLengthOrRange_isAMsgPackException() {
        assertThrows(MsgPackException.class, () -> MsgTimestamps.decodePayload(new byte[5]));
        byte[] outOfRange = new byte[12];
        outOfRange[4] = 0x7f;
        assertThrows(MsgPackException.class, () -> MsgTimestamps.decodePayload(outOfRange));
        assertThrows(MsgPackException.class, () -> MsgTimestamps.decode(MsgExtension.of((byte) 1, new byte[4])));
    }

    private static Instant coreTimestamp(byte[] frame) {
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(frame)) {
            ExtensionTypeHeader header = unpacker.unpackExtensionTypeHeader();
            return unpacker.unpackTimestamp(header);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
