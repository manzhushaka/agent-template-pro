package com.manzhushaka.agent.common.mask;

public final class SensitiveMasker {
    private SensitiveMasker() { }
    public static String phone(String value) {
        if (value == null || value.length() < 7) return value;
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }
}
