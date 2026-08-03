package com.manzhushaka.agent.runtime.chat;

/**
 * Published Agent application runtime context. The open Agent API resolves this from the
 * immutable published version and passes it into the shared Runtime chain, so the pinned
 * model, prompt and knowledge base affect the same conversation, task and audit pipeline
 * used by the visitor chat.
 */
public record AgentAppRuntimeContext(
        String appCode,
        String appDisplayName,
        String systemPromptOverride,
        String knowledgeContext,
        String modelCode
) {
    public AgentAppRuntimeContext {
        appCode = appCode == null ? "" : appCode;
        appDisplayName = appDisplayName == null ? appCode : appDisplayName;
        modelCode = modelCode == null ? "" : modelCode;
    }
}
