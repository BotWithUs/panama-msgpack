package com.botwithus.msgpack;

/**
 * A MessagePack float. It keeps the width it arrived in, so a decoded value
 * packs back to the same bytes.
 *
 * @param value             the value, widened to a double
 * @param isSinglePrecision whether it is, or is to be written as, a float32
 */
public record MsgFloat(double value, boolean isSinglePrecision) implements MsgValue {

    /** @return {@code value} as a float64 */
    public static MsgFloat of(double value) {
        return new MsgFloat(value, false);
    }

    /** @return {@code value} as a float32 */
    public static MsgFloat ofSingle(float value) {
        return new MsgFloat(value, true);
    }

    @Override
    public MsgType type() {
        return MsgType.FLOAT;
    }
}
