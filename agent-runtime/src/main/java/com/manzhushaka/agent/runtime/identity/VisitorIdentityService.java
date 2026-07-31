package com.manzhushaka.agent.runtime.identity;

public interface VisitorIdentityService {
    String resolve(String signedCookieValue);
    VisitorCookie cookie(String visitorId);
}
