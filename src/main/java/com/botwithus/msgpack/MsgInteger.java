package com.botwithus.msgpack;

import java.math.BigInteger;

/**
 * A MessagePack integer: any value from {@code -2^63} to {@code 2^64 - 1}.
 * Equality is numeric, so the wire format it arrived in does not matter.
 *
 * @param bits       the value's 64 bits
 * @param isUnsigned whether {@code bits} is read as unsigned. It is true only
 *                   when the value is above {@link Long#MAX_VALUE}, so each
 *                   number has exactly one representation
 */
public record MsgInteger(long bits, boolean isUnsigned) implements MsgValue {

    /** Normalises a non-negative unsigned value to its signed spelling. */
    public MsgInteger {
        isUnsigned = isUnsigned && bits < 0;
    }

    /** @return the signed integer {@code value} */
    public static MsgInteger of(long value) {
        return new MsgInteger(value, false);
    }

    /** @return the integer whose unsigned 64-bit representation is {@code bits} */
    public static MsgInteger ofUnsigned(long bits) {
        return new MsgInteger(bits, true);
    }

    /**
     * @return {@code value} as a MessagePack integer
     * @throws IllegalArgumentException if it is outside {@code -2^63 .. 2^64 - 1}
     */
    public static MsgInteger of(BigInteger value) {
        if (value.signum() < 0 || value.bitLength() < Long.SIZE) {
            if (value.bitLength() >= Long.SIZE) {
                throw new IllegalArgumentException(value + " is below the MessagePack integer range");
            }
            return of(value.longValue());
        }
        if (value.bitLength() > Long.SIZE) {
            throw new IllegalArgumentException(value + " is above the MessagePack integer range");
        }
        return ofUnsigned(value.longValue());
    }

    @Override
    public MsgType type() {
        return MsgType.INTEGER;
    }

    /** @return whether the value fits a {@code long} */
    public boolean isInLongRange() {
        return !isUnsigned;
    }

    /** @return whether the value fits an {@code int} */
    public boolean isInIntRange() {
        return !isUnsigned && bits >= Integer.MIN_VALUE && bits <= Integer.MAX_VALUE;
    }

    /**
     * @return the value as a {@code long}
     * @throws MsgPackException if it is above {@link Long#MAX_VALUE}
     */
    public long asLong() {
        if (isUnsigned) {
            throw new MsgPackException("integer " + this + " does not fit a long");
        }
        return bits;
    }

    /**
     * @return the value as an {@code int}
     * @throws MsgPackException if it does not fit one
     */
    public int asInt() {
        if (!isInIntRange()) {
            throw new MsgPackException("integer " + this + " does not fit an int");
        }
        return (int) bits;
    }

    /** @return the exact value */
    public BigInteger toBigInteger() {
        return isUnsigned ? new BigInteger(Long.toUnsignedString(bits)) : BigInteger.valueOf(bits);
    }

    @Override
    public String toString() {
        return isUnsigned ? Long.toUnsignedString(bits) : Long.toString(bits);
    }
}
