package com.botwithus.msgpack;

import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** msgpack-core 0.9.8, the reference both the packer and the unpacker are measured against. */
final class CoreOracle {

    /** One call against a msgpack-core packer. */
    @FunctionalInterface
    interface PackerCall {
        void apply(MessageBufferPacker packer) throws IOException;
    }

    private CoreOracle() {
    }

    /** @return the bytes msgpack-core writes for {@code call} */
    static byte[] pack(PackerCall call) {
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            call.apply(packer);
            return packer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return msgpack-core's value tree for the first value in {@code data} */
    static Value unpack(byte[] data) {
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(data)) {
            return unpacker.unpackValue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return {@code value} as a msgpack-core value; a float becomes a double, as msgpack-core keeps none */
    static Value toCore(MsgValue value) {
        return switch (value) {
            case MsgNil n -> ValueFactory.newNil();
            case MsgBoolean b -> ValueFactory.newBoolean(b.value());
            case MsgInteger i -> ValueFactory.newInteger(i.toBigInteger());
            case MsgFloat f -> ValueFactory.newFloat(f.value());
            case MsgString s -> ValueFactory.newString(s.toByteArray());
            case MsgBinary b -> ValueFactory.newBinary(b.toByteArray());
            case MsgExtension e -> ValueFactory.newExtension(e.extType(), e.toByteArray());
            case MsgArray a -> ValueFactory.newArray(a.values().stream().map(CoreOracle::toCore).toList());
            case MsgMap m -> {
                List<Value> kv = new ArrayList<>();
                m.entries().forEach(e -> {
                    kv.add(toCore(e.key()));
                    kv.add(toCore(e.value()));
                });
                yield ValueFactory.newMap(kv.toArray(new Value[0]));
            }
        };
    }
}
