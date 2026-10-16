package com.victorpena.just_in_transit;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;

/** Random browser credential; only its SHA-256 digest is stored with personal records. */
@Component
public class BrowserIdentity {
    static final String COOKIE="JIT_BROWSER", ATTRIBUTE="jit.browser.owner", SESSION_TOKEN="jit.browser.token";
    static String newToken() { byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static boolean valid(String token) { return token!=null && token.matches("[A-Za-z0-9_-]{43}"); }
    static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public String id() {
        var attrs=RequestContextHolder.getRequestAttributes();
        if(attrs instanceof ServletRequestAttributes servlet && servlet.getRequest().getAttribute(ATTRIBUTE) instanceof String owner)return owner;
        throw new IllegalStateException("A browser identity is required for this operation.");
    }
}
