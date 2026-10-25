package com.botwithus.msgpack;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Writes MessagePack by hand, format byte by format byte, so a test can choose
 * any encoding the specification allows, minimal or not. Written independently
 * of {@link MsgPacker}: it shares only the spec's format bytes.
 */
final class RawWriter {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(bytes);

    byte[] toByteArray() {
        return bytes.toByteArray();
    }

    RawWriter u8(int value) {
        try {
            out.writeByte(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    RawWriter u16(int value) {
        try {
            out.writeShort(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    RawWriter u32(long value) {
        try {
            out.writeInt((int) value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    RawWriter u64(long value) {
        try {
            out.writeLong(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    RawWriter f32(float value) {
        return u8(Codes.FLOAT32).u32(Float.floatToRawIntBits(value));
    }

    RawWriter f64(double value) {
        return u8(Codes.FLOAT64).u64(Double.doubleToRawLongBits(value));
    }

    RawWriter bytes(byte[] data) {
        bytes.writeBytes(data);
        return this;
    }

    /** A length or count header: {@code family} is 8, 16 or 32 bits, or 0 for the fix form. */
    RawWriter header(int fixPrefix, int code8, int code16, int code32, int family, long length) {
        return switch (family) {
            case 0 -> u8(fixPrefix | (int) length);
            case Byte.SIZE -> u8(code8).u8((int) length);
            case Short.SIZE -> u8(code16).u16((int) length);
            default -> u8(code32).u32(length);
        };
    }

    RawWriter str(byte[] utf8, int family) {
        return header(Codes.FIXSTR_PREFIX, Codes.STR8, Codes.STR16, Codes.STR32, family, utf8.length).bytes(utf8);
    }

    RawWriter bin(byte[] data, int family) {
        return header(0, Codes.BIN8, Codes.BIN16, Codes.BIN32, family, data.length).bytes(data);
    }

    RawWriter arrayHeader(int count, int family) {
        return header(Codes.FIXARRAY_PREFIX, 0, Codes.ARRAY16, Codes.ARRAY32, family, count);
    }

    RawWriter mapHeader(int count, int family) {
        return header(Codes.FIXMAP_PREFIX, 0, Codes.MAP16, Codes.MAP32, family, count);
    }

    /** An extension: {@code isFix} chooses fixext, which needs a 1/2/4/8/16-byte payload. */
    RawWriter ext(byte type, byte[] data, boolean isFix, int family) {
        if (isFix) {
            int code = switch (data.length) {
                case Codes.FIXEXT1_LENGTH -> Codes.FIXEXT1;
                case Codes.FIXEXT2_LENGTH -> Codes.FIXEXT2;
                case Codes.FIXEXT4_LENGTH -> Codes.FIXEXT4;
                case Codes.FIXEXT8_LENGTH -> Codes.FIXEXT8;
                default -> Codes.FIXEXT16;
            };
            return u8(code).u8(type).bytes(data);
        }
        return header(0, Codes.EXT8, Codes.EXT16, Codes.EXT32, family, data.length).u8(type).bytes(data);
    }
}
