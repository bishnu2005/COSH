package com.cosh.core.error;

/**
 * Runtime exception used uniformly across all COSH modules.
 * Carries a structured {@link ErrorCode} for programmatic handling.
 *
 * <p>
 * Replaces the old {@code RedisMiniException}.
 */
public class CoshException extends RuntimeException {

    private final ErrorCode errorCode;

    public CoshException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public CoshException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    @Override
    public String toString() {
        return "[" + errorCode.getCode() + "] " + getMessage();
    }
}
