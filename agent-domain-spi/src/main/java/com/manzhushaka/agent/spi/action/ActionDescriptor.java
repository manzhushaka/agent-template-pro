package com.manzhushaka.agent.spi.action;

import java.util.List;
public record ActionDescriptor(String code, String displayName, ActionMode mode, List<String> requiredFields, String confirmationTitle) { }
