package com.botwithus.msgpack;

/** @param value the boolean */
public record MsgBoolean(boolean value) implements MsgValue {

    @Override
    public MsgType type() {
        return MsgType.BOOLEAN;
    }
}
