package com.manzhushaka.agent.common.mask;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveMaskerTest {
    @Test
    void masksChinesePhoneAndCardNumbersInText() {
        String text = "请联系 13812345678 或证件 110101199003071234，银行卡 6222021234567890123";
        String masked = SensitiveMasker.maskText(text);
        assertFalse(masked.contains("13812345678"));
        assertFalse(masked.contains("110101199003071234"));
        assertFalse(masked.contains("6222021234567890123"));
        assertTrue(SensitiveMasker.isClean(masked));
    }

    @Test
    void masksEmailAndBearerCredentials() {
        String text = "授权 Authorization: Bearer abcDEF123xyz456，邮箱 a@b.com";
        String masked = SensitiveMasker.maskText(text);
        assertFalse(masked.contains("abcDEF123xyz456"));
        assertFalse(masked.contains("a@b.com"));
    }

    @Test
    void masksUrlCredentials() {
        String masked = SensitiveMasker.maskText("http://user:pass@example.com/x");
        assertFalse(masked.contains("user:pass@"));
        assertTrue(masked.contains("example.com"));
    }

    @Test
    void masksMapBySensitiveKeyAndKeepsKeys() {
        Map<String, Object> input = Map.of(
                "phone", "13812345678",
                "summary", "客服电话 13812345678 已回访",
                "bookingNo", "BK-2026-01",
                "nested", Map.of("apiKey", "sk-abc", "city", "上海")
        );
        Map<String, Object> masked = SensitiveMasker.maskValue(input);
        assertEquals("[masked]", masked.get("phone"));
        assertEquals("BK-2026-01", masked.get("bookingNo"));
        assertFalse(String.valueOf(masked.get("summary")).contains("13812345678"));
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) masked.get("nested");
        assertEquals("[masked]", nested.get("apiKey"));
        assertEquals("上海", nested.get("city"));
    }

    @Test
    void masksListElements() {
        List<String> masked = SensitiveMasker.maskValue(List.of("13812345678", "正常内容"));
        assertFalse(masked.get(0).contains("13812345678"));
        assertEquals("正常内容", masked.get(1));
    }
}
