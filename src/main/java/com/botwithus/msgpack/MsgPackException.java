package com.botwithus.msgpack;

import java.io.Serial;

/**
 * Thrown for input this library will not read: a truncated frame, a length
 * that runs past the end, a type other than the one asked for, nesting past
 * the limit, invalid UTF-8 where a string was asked for, a value outside the
 * range of the requested Java type, or a rule of a {@link MsgDecodeRules}.
 *
 * <p>The decoder throws nothing else for bad input. Any other exception out of
 * a read is a bug in this library.
 */
public class MsgPackException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** @param message what was wrong, and where when it is known */
    public MsgPackException(String message) {
        super(message);
    }

    /**
     * @param message what was wrong
     * @param cause   the lower-level failure
     */
    public MsgPackException(String message, Throwable cause) {
        super(message, cause);
    }
}
