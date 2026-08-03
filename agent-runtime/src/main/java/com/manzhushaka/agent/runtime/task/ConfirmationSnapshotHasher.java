package com.manzhushaka.agent.runtime.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Produces a stable hash over the exact action and structured input shown for confirmation. */
@Component
public final class ConfirmationSnapshotHasher {
    private final ObjectMapper canonicalMapper;

    public ConfirmationSnapshotHasher(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String hash(String actionCode, Map<String, Object> input) {
        try {
            byte[] snapshot = canonicalMapper.writeValueAsBytes(Map.of(
                    "actionCode", actionCode,
                    "input", input
            ));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Confirmation input is not JSON serializable", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }
}
