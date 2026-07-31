package com.manzhushaka.agent.infrastructure.identity;

import com.manzhushaka.agent.runtime.identity.VisitorIdentityService;
import com.manzhushaka.agent.runtime.identity.VisitorCookie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

@Service
public class HmacVisitorIdentityService implements VisitorIdentityService {
    private final String key; private final String cookieName;
    public HmacVisitorIdentityService(@Value("${agent.visitor.signing-key:local-development-key-change-me}") String key, @Value("${agent.visitor.cookie-name:agent_visitor}") String cookieName) { this.key=key; this.cookieName=cookieName; }
    public String resolve(String signed) { if (signed == null || !signed.contains(".")) return UUID.randomUUID().toString(); String[] parts=signed.split("\\.", 2); return MessageDigest.isEqual(signature(parts[0]).getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8)) ? parts[0] : UUID.randomUUID().toString(); }
    public VisitorCookie cookie(String visitorId) { return new VisitorCookie(cookieName, visitorId + "." + signature(visitorId), 180 * 24 * 3600L); }
    private String signature(String source) { try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(source.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException("Unable to sign visitor identity", e); } }
}
