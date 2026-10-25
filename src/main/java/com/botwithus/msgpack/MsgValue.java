package com.botwithus.msgpack;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One immutable MessagePack value. The variants are a closed set, so a caller
 * that needs every case dispatches with a {@code switch} over this type. The
 * optional accessors below cover the common "read this field if it is the type
 * I expect" case, where a value of another type reads as absent.
 */
public sealed interface MsgValue
        permits MsgNil, MsgBoolean, MsgInteger, MsgFloat, MsgString, MsgBinary, MsgArray, MsgMap, MsgExtension {

    /** @return which of the nine MessagePack types this is */
    MsgType type();

    /** @return the nil value */
    static MsgValue nil() {
        return new MsgNil();
    }

    /** @return {@code value} as a MessagePack boolean */
    static MsgValue of(boolean value) {
        return new MsgBoolean(value);
    }

    /** @return {@code value} as a signed MessagePack integer */
    static MsgValue of(long value) {
        return MsgInteger.of(value);
    }

    /** @return {@code value} as a MessagePack float64 */
    static MsgValue of(double value) {
        return MsgFloat.of(value);
    }

    /** @return {@code value} as a MessagePack string, encoded as UTF-8 */
    static MsgValue of(String value) {
        return MsgString.of(value);
    }

    /** @return a copy of {@code value} as MessagePack binary */
    static MsgValue of(byte[] value) {
        return MsgBinary.of(value);
    }

    /** @return {@code values} as a MessagePack array */
    static MsgValue of(List<? extends MsgValue> values) {
        return new MsgArray(List.copyOf(values));
    }

    /** @return whether this is nil */
    default boolean isNil() {
        return type() == MsgType.NIL;
    }

    /** @return this boolean's value; empty for any other type */
    default Optional<Boolean> booleanValue() {
        return switch (this) {
            case MsgBoolean b -> Optional.of(b.value());
            default -> Optional.empty();
        };
    }

    /** @return this integer, if it is one and fits a {@code long}; empty otherwise */
    default OptionalLong longValue() {
        return switch (this) {
            case MsgInteger i when i.isInLongRange() -> OptionalLong.of(i.asLong());
            default -> OptionalLong.empty();
        };
    }

    /** @return this integer; empty for any other type */
    default Optional<MsgInteger> integerValue() {
        return switch (this) {
            case MsgInteger i -> Optional.of(i);
            default -> Optional.empty();
        };
    }

    /**
     * @return this string, decoded; empty for any other type
     * @throws MsgPackException if this is a string whose bytes are not valid UTF-8
     */
    default Optional<String> stringValue() {
        return switch (this) {
            case MsgString s -> Optional.of(s.asString());
            default -> Optional.empty();
        };
    }

    /** @return a copy of this binary's bytes; empty for any other type */
    default Optional<byte[]> binaryValue() {
        return switch (this) {
            case MsgBinary b -> Optional.of(b.toByteArray());
            default -> Optional.empty();
        };
    }

    /** @return this array; empty for any other type */
    default Optional<MsgArray> arrayValue() {
        return switch (this) {
            case MsgArray a -> Optional.of(a);
            default -> Optional.empty();
        };
    }

    /** @return this map; empty for any other type */
    default Optional<MsgMap> mapValue() {
        return switch (this) {
            case MsgMap m -> Optional.of(m);
            default -> Optional.empty();
        };
    }
}
