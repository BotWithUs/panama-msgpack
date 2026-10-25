package com.botwithus.msgpack;

/** The MessagePack nil. Every instance is equal to every other. */
public record MsgNil() implements MsgValue {

    @Override
    public MsgType type() {
        return MsgType.NIL;
    }
}
