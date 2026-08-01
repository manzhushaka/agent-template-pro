package com.manzhushaka.agent.runtime.chat;

/** Generates the coordinator's user-facing response after routing has kept the conversation at coordinator level. */
public interface CoordinatorConversationResponder {
    CoordinatorConversationReply respond(CoordinatorConversationRequest request);
}
