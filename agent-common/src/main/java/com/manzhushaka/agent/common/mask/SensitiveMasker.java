package com.manzhushaka.agent.common.mask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic masking for fields that must never reach traces, exports or error responses.
 * The masking rules are intentionally conservative: any matched pattern is replaced with a
 * stable placeholder so masked text can be compared against the original to detect leaks.
 */
public final class SensitiveMasker {
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])(1[3-9]\\d{9})(?![0-9])");
    private static final Pattern ID_CARD = Pattern.compile("(?<![0-9A-Za-z])(\\d{15}|\\d{17}[0-9Xx])(?![0-9A-Za-z])");
    private static final Pattern BANK_CARD = Pattern.compile("(?<![0-9])(\\d{16,19})(?![0-9])");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+|api[_-]?key\\s*[:=]\\s*|secret\\s*[:=]\\s*|password\\s*[:=]\\s*|token\\s*[:=]\\s*)([A-Za-z0-9._~+/=-]{8,})");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("([a-z][a-z0-9+.-]*://)[^/@\\s]+@");
    private static final List<String> SENSITIVE_KEYS = List.of(
            "password", "passwd", "pwd", "secret", "token", "apiKey", "api_key", "apikey",
            "accessKey", "access_key", "privateKey", "private_key", "credential", "credentials",
            "signature", "sign", "authorization", "cookie", "visitorId", "visitor_id", "idCard",
            "id_card", "bankCard", "bank_card", "cardNo", "card_no", "phone", "mobile", "email",
            "rawRequest", "raw_request", "requestBody", "request_body"
    );

    private SensitiveMasker() { }

    public static String phone(String value) {
        if (value == null || value.length() < 7) return value;
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    /** Masks phones, ID/bank card numbers, emails, credentials and URL credentials in free text. */
    public static String maskText(String value) {
        if (value == null) return null;
        String masked = value;
        masked = PHONE.matcher(masked).replaceAll("138****0000");
        masked = ID_CARD.matcher(masked).replaceAll("110****0000");
        masked = BANK_CARD.matcher(masked).replaceAll("6222**********0000");
        masked = EMAIL.matcher(masked).replaceAll("masked@example.com");
        masked = BEARER.matcher(masked).replaceAll("$1[masked]");
        masked = URL_CREDENTIALS.matcher(masked).replaceAll("$1[masked]@");
        return masked;
    }

    /** Deep-masks map/list values: sensitive keys are replaced, free text is pattern-masked. */
    @SuppressWarnings("unchecked")
    public static <T> T maskValue(T value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> maskedMap = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String keyText = String.valueOf(key);
                if (isSensitiveKey(keyText)) {
                    maskedMap.put(keyText, "[masked]");
                } else {
                    maskedMap.put(keyText, maskValue(item));
                }
            });
            return (T) maskedMap;
        }
        if (value instanceof List<?> list) {
            return (T) list.stream().map(SensitiveMasker::maskValue).toList();
        }
        if (value instanceof String text) {
            return (T) maskText(text);
        }
        return value;
    }

    /** Returns true when the text contains no detectable sensitive content. */
    public static boolean isClean(String value) {
        if (value == null || value.isBlank()) return true;
        String masked = maskText(value);
        return masked.equals(value);
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.trim().toLowerCase(java.util.Locale.ROOT);
        return SENSITIVE_KEYS.stream().anyMatch(normalized::equals)
                || normalized.endsWith("_hash")
                || normalized.endsWith("secret");
    }
}
