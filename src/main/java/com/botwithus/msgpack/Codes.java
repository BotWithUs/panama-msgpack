package com.botwithus.msgpack;

/**
 * The format bytes of the MessagePack specification, plus the size bounds of
 * each family. Spec values, named once for the packer and the unpacker.
 */
final class Codes {

    static final int POSITIVE_FIXINT_MAX = 0x7f;
    static final int FIXMAP_PREFIX = 0x80;
    static final int FIXMAP_MAX = 0x8f;
    static final int FIXARRAY_PREFIX = 0x90;
    static final int FIXARRAY_MAX = 0x9f;
    static final int FIXSTR_PREFIX = 0xa0;
    static final int FIXSTR_MAX = 0xbf;
    static final int NIL = 0xc0;
    static final int NEVER_USED = 0xc1;
    static final int FALSE = 0xc2;
    static final int TRUE = 0xc3;
    static final int BIN8 = 0xc4;
    static final int BIN16 = 0xc5;
    static final int BIN32 = 0xc6;
    static final int EXT8 = 0xc7;
    static final int EXT16 = 0xc8;
    static final int EXT32 = 0xc9;
    static final int FLOAT32 = 0xca;
    static final int FLOAT64 = 0xcb;
    static final int UINT8 = 0xcc;
    static final int UINT16 = 0xcd;
    static final int UINT32 = 0xce;
    static final int UINT64 = 0xcf;
    static final int INT8 = 0xd0;
    static final int INT16 = 0xd1;
    static final int INT32 = 0xd2;
    static final int INT64 = 0xd3;
    static final int FIXEXT1 = 0xd4;
    static final int FIXEXT2 = 0xd5;
    static final int FIXEXT4 = 0xd6;
    static final int FIXEXT8 = 0xd7;
    static final int FIXEXT16 = 0xd8;
    static final int STR8 = 0xd9;
    static final int STR16 = 0xda;
    static final int STR32 = 0xdb;
    static final int ARRAY16 = 0xdc;
    static final int ARRAY32 = 0xdd;
    static final int MAP16 = 0xde;
    static final int MAP32 = 0xdf;
    static final int NEGATIVE_FIXINT_MIN = 0xe0;

    /** Element count or byte length a fix-format header can carry, exclusive. */
    static final int FIXMAP_LIMIT = 16;
    static final int FIXARRAY_LIMIT = 16;
    static final int FIXSTR_LIMIT = 32;
    /** Lower bound of a negative fixint, as a signed value. */
    static final int NEGATIVE_FIXINT_FLOOR = -32;
    /** Mask that extracts the count or length from a fix-format byte. */
    static final int FIXMAP_MASK = 0x0f;
    static final int FIXSTR_MASK = 0x1f;
    /** Exclusive upper bounds of the 8- and 16-bit unsigned length fields. */
    static final int U8_LIMIT = 1 << Byte.SIZE;
    static final int U16_LIMIT = 1 << Short.SIZE;
    /** Exclusive upper bound of a 32-bit unsigned field. */
    static final long U32_LIMIT = 1L << Integer.SIZE;
    /** Mask that widens an unsigned byte, short or int to a non-negative value. */
    static final int U8_MASK = 0xff;
    static final int U16_MASK = 0xffff;
    static final long U32_MASK = 0xffff_ffffL;

    /** Payload lengths of the five fixext formats, in format order. */
    static final int FIXEXT1_LENGTH = 1;
    static final int FIXEXT2_LENGTH = 2;
    static final int FIXEXT4_LENGTH = 4;
    static final int FIXEXT8_LENGTH = 8;
    static final int FIXEXT16_LENGTH = 16;

    private Codes() {
    }

    /**
     * @return the value type a format byte introduces
     * @throws MsgPackException for {@code 0xc1}, which the spec never assigns
     */
    static MsgType typeOf(int code) {
        if (code <= POSITIVE_FIXINT_MAX || code >= NEGATIVE_FIXINT_MIN) {
            return MsgType.INTEGER;
        }
        if (code <= FIXMAP_MAX) {
            return MsgType.MAP;
        }
        if (code <= FIXARRAY_MAX) {
            return MsgType.ARRAY;
        }
        if (code <= FIXSTR_MAX) {
            return MsgType.STRING;
        }
        return typeOfTagged(code);
    }

    private static MsgType typeOfTagged(int code) {
        return switch (code) {
            case NIL -> MsgType.NIL;
            case FALSE, TRUE -> MsgType.BOOLEAN;
            case BIN8, BIN16, BIN32 -> MsgType.BINARY;
            case EXT8, EXT16, EXT32, FIXEXT1, FIXEXT2, FIXEXT4, FIXEXT8, FIXEXT16 -> MsgType.EXTENSION;
            case FLOAT32, FLOAT64 -> MsgType.FLOAT;
            case UINT8, UINT16, UINT32, UINT64, INT8, INT16, INT32, INT64 -> MsgType.INTEGER;
            case STR8, STR16, STR32 -> MsgType.STRING;
            case ARRAY16, ARRAY32 -> MsgType.ARRAY;
            case MAP16, MAP32 -> MsgType.MAP;
            default -> throw new MsgPackException("format byte 0x" + Integer.toHexString(code)
                    + " is reserved by the MessagePack specification and never used");
        };
    }
}
