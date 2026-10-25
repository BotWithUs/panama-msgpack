/**
 * A MessagePack codec with no dependencies and no {@code sun.misc.Unsafe}.
 *
 * <p>Every read and write goes through {@link java.lang.foreign.MemorySegment}
 * with big-endian unaligned value layouts, over a heap array or a segment the
 * caller supplies. Nothing here is a restricted method, so a consumer needs no
 * {@code --enable-native-access} grant for this module.
 */
module com.botwithus.msgpack {
    exports com.botwithus.msgpack;
}
