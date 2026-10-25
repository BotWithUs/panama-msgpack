package com.botwithus.msgpack;

import com.botwithus.msgpack.adapters.MapCodecAdapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A rough throughput comparison against msgpack-core, run with
 * {@code ./gradlew bench}. Not JMH and not a gate: it warms up, times a fixed
 * number of operations, and prints operations per second per codec.
 */
public final class CodecBench {

    private static final int WARMUP_ROUNDS = 3;
    private static final int TIMED_ROUNDS = 5;
    private static final int OPS_PER_ROUND = 200_000;
    private static final double NANOS_PER_SECOND = 1e9;

    private CodecBench() {
    }

    public static void main(String[] args) {
        Map<String, Object> request = request();
        Map<String, Object> reply = reply();
        byte[] replyBytes = LegacyCodec.encode(reply);
        System.out.printf(Locale.ROOT, "request %d bytes, reply %d bytes%n",
                LegacyCodec.encode(request).length, replyBytes.length);
        report("encode request  msgpack-core", () -> LegacyCodec.encode(request));
        report("encode request  this library", () -> MapCodecAdapter.encode(request));
        report("decode reply    msgpack-core", () -> LegacyCodec.decode(replyBytes));
        report("decode reply    this library", () -> MapCodecAdapter.decode(replyBytes));
        report("tree decode     msgpack-core", () -> CoreOracle.unpack(replyBytes));
        report("tree decode     this library", () -> MsgPack.decode(replyBytes));
    }

    /** Shaped like an RPC call: a method name, an id and a small parameter map. */
    private static Map<String, Object> request() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("interfaceId", 1477);
        params.put("componentId", 25);
        params.put("option", "Withdraw-All");
        params.put("timeoutMs", 5000L);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", 123456);
        request.put("method", "get_component");
        request.put("params", params);
        return request;
    }

    /** Shaped like a list reply: a few dozen flat maps of ints and short strings. */
    private static Map<String, Object> reply() {
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("id", 1_000_000 + i * 7);
            row.put("name", "Component " + i);
            row.put("visible", i % 3 != 0);
            row.put("x", i * 13);
            row.put("y", -i * 5);
            rows.add(row);
        }
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("id", 123456);
        reply.put("result", rows);
        return reply;
    }

    private static void report(String label, Supplier<Object> op) {
        Object sink = null;
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            for (int i = 0; i < OPS_PER_ROUND; i++) {
                sink = op.get();
            }
        }
        long start = System.nanoTime();
        for (int round = 0; round < TIMED_ROUNDS; round++) {
            for (int i = 0; i < OPS_PER_ROUND; i++) {
                sink = op.get();
            }
        }
        double seconds = (System.nanoTime() - start) / NANOS_PER_SECOND;
        System.out.printf(Locale.ROOT, "%-30s %,12.0f ops/s  (%s)%n", label,
                TIMED_ROUNDS * OPS_PER_ROUND / seconds, sink == null ? "-" : "ok");
    }
}
