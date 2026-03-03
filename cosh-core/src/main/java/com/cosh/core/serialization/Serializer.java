package com.cosh.core.serialization;

import com.cosh.core.error.CoshException;
import com.cosh.core.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin JSON serialization utility used for value encoding in future phases.
 *
 * <p>
 * Will be used extensively in Phase 3 (binary protocol) for encoding
 * complex value types beyond plain strings.
 */
public final class Serializer {

    private static final Logger log = LoggerFactory.getLogger(Serializer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Serializer() {
    }

    /**
     * Serialize an object to a JSON string.
     *
     * @param object the object to serialize
     * @return JSON representation
     * @throws CoshException if serialization fails
     */
    public static String serialize(Object object) {
        try {
            return MAPPER.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("Serialization failed for type {}", object.getClass().getSimpleName(), e);
            throw new CoshException(ErrorCode.INTERNAL_ERROR, "Failed to serialize object", e);
        }
    }

    /**
     * Deserialize a JSON string to a typed object.
     *
     * @param json  the JSON string
     * @param clazz the target type
     * @param <T>   target type parameter
     * @return the deserialized object
     * @throws CoshException if deserialization fails
     */
    public static <T> T deserialize(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("Deserialization failed for type {}", clazz.getSimpleName(), e);
            throw new CoshException(ErrorCode.INTERNAL_ERROR,
                    "Failed to deserialize JSON to " + clazz.getSimpleName(), e);
        }
    }
}
