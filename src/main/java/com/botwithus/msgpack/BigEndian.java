package com.botwithus.msgpack;

import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * The value layouts every multi-byte field is read and written through:
 * MessagePack is big-endian and a field may sit at any offset.
 */
final class BigEndian {

    static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private BigEndian() {
    }
}
