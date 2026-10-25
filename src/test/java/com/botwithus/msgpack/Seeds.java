package com.botwithus.msgpack;

import java.util.Random;
import java.util.function.Consumer;

/**
 * Runs seeded cases and names the seed in any failure. Every run draws a fresh
 * base seed unless {@code -Dmsgpack.seed=<n>} pins one, so a failure can be
 * replayed exactly: rerun with the base seed it prints.
 */
final class Seeds {

    private static final String SEED_PROPERTY = "msgpack.seed";

    private Seeds() {
    }

    /** @return the base seed for this run: the pinned one, or a fresh one */
    static long baseSeed() {
        String pinned = System.getProperty(SEED_PROPERTY);
        return pinned != null ? Long.parseLong(pinned) : System.nanoTime();
    }

    /**
     * Runs {@code body} {@code cases} times, each with its own {@link Random}
     * derived from the base seed and the case index.
     */
    static void run(String name, int cases, Consumer<Random> body) {
        long base = baseSeed();
        for (int i = 0; i < cases; i++) {
            Random random = new Random(base * 31 + i);
            try {
                body.accept(random);
            } catch (Throwable t) {
                throw new AssertionError(name + " failed at case " + i + " of base seed " + base
                        + " (replay with -Pmsgpack.seed=" + base + ")", t);
            }
        }
    }
}
