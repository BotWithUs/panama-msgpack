package com.botwithus.msgpack;

/**
 * The header of an extension value, as read by {@link MsgUnpacker#unpackExtensionHeader()}.
 * The payload of {@code length} bytes follows it.
 *
 * @param extType the application type code
 * @param length  the payload length in bytes, already checked against the bytes left
 */
public record MsgExtensionHeader(byte extType, int length) {
}
