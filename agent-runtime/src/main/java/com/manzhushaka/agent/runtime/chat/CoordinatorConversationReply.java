package com.manzhushaka.agent.runtime.chat;

public record CoordinatorConversationReply(String content, String generationSource) {
    public static final String MODEL = "MODEL";
    public static final String PRESET_FALLBACK = "PRESET_FALLBACK";
}
