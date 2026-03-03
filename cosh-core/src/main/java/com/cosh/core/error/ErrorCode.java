package com.cosh.core.error;

/**
 * Structured error codes used across COSH modules.
 * All codes are prefixed to avoid ambiguity.
 */
public enum ErrorCode {

    // System Errors
    INTERNAL_ERROR("COSH_001", "Internal system error"),
    CONFIGURATION_ERROR("COSH_002", "Configuration error"),
    INVALID_ARGUMENT("COSH_003", "Invalid argument provided"),

    // Store Errors
    KEY_NOT_FOUND("COSH_101", "Key not found"),
    STORE_FULL("COSH_102", "Cache store is full"),

    // Distribution Errors
    NODE_UNAVAILABLE("COSH_201", "Cache node unavailable"),
    NO_NODES_AVAILABLE("COSH_202", "No cache nodes available in the ring");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
