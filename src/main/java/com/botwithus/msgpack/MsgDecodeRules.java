package com.botwithus.msgpack;

/**
 * What {@link MsgPack#decode} insists on beyond well-formed MessagePack. Start
 * from {@link #strict()} and relax what a caller's existing contract needs.
 *
 * @param maxDepth                how deeply containers may nest, as in
 *                                {@link MsgUnpacker#unpackValue(int)}
 * @param isTrailingDataRejected  whether bytes after the first value are an error
 *                                rather than ignored
 * @param isMapRootRequired       whether the first value must be a map
 */
public record MsgDecodeRules(int maxDepth, boolean isTrailingDataRejected, boolean isMapRootRequired) {

    /** The nesting limit of {@link #strict()}: far deeper than any real document. */
    public static final int DEFAULT_MAX_DEPTH = 128;

    /** Rejects a negative depth. */
    public MsgDecodeRules {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must not be negative: " + maxDepth);
        }
    }

    /**
     * @return exactly one value, nested at most {@value #DEFAULT_MAX_DEPTH} deep,
     *         with nothing after it; any type at the root
     */
    public static MsgDecodeRules strict() {
        return new MsgDecodeRules(DEFAULT_MAX_DEPTH, true, false);
    }

    /** @return these rules with a different nesting limit */
    public MsgDecodeRules withMaxDepth(int depth) {
        return new MsgDecodeRules(depth, isTrailingDataRejected, isMapRootRequired);
    }

    /** @return these rules, with bytes after the first value ignored */
    public MsgDecodeRules allowingTrailingData() {
        return new MsgDecodeRules(maxDepth, false, isMapRootRequired);
    }

    /** @return these rules, with the first value required to be a map */
    public MsgDecodeRules requiringMapRoot() {
        return new MsgDecodeRules(maxDepth, isTrailingDataRejected, true);
    }
}
