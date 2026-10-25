package com.botwithus.msgpack;

import java.util.List;

/** @param values the elements in wire order, as an unmodifiable copy */
public record MsgArray(List<MsgValue> values) implements MsgValue {

    /** Copies {@code values}, so the array stays immutable. */
    public MsgArray {
        values = List.copyOf(values);
    }

    @Override
    public MsgType type() {
        return MsgType.ARRAY;
    }

    /** @return the number of elements */
    public int size() {
        return values.size();
    }

    /** @return the element at {@code index} */
    public MsgValue get(int index) {
        return values.get(index);
    }
}
