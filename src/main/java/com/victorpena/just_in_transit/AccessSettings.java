package com.victorpena.just_in_transit;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AccessSettings {
    private static final Logger log = LoggerFactory.getLogger(AccessSettings.class);
    private final boolean enabled;
    private final boolean publicAccess;
    private final String username, password;

    // Preserve the guarded four-argument constructor used by existing tests/callers.
    public AccessSettings(boolean enabled, String address, String username, String password) {
        this(enabled, address, username, password, false);
    }

    @Autowired
    public AccessSettings(@Value("${jit.access.enabled:false}") boolean enabled,
                          @Value("${server.address:127.0.0.1}") String address,
                          @Value("${jit.access.username:victor}") String username,
                          @Value("${jit.access.password:}") String password,
                          @Value("${jit.access.allow-public:false}") boolean allowPublic) {
        if (enabled && allowPublic) {
            throw new IllegalStateException("Choose protected access or public access, not both.");
        }
        if (!enabled && !allowPublic && !Set.of("127.0.0.1", "::1", "[::1]").contains(address)) {
            throw new IllegalStateException("Non-loopback access requires the phone profile with a password "
                    + "or an explicit public profile for access without login.");
        }
        if (enabled && (username.isBlank() || password.isBlank() || password.length() < 16
                || password.getBytes(StandardCharsets.UTF_8).length > 72)) {
            throw new IllegalStateException("Protected access requires a username and JIT_PASSWORD "
                    + "with at least 16 characters and no more than 72 UTF-8 bytes.");
        }
        this.enabled = enabled;
        this.publicAccess = allowPublic;
        this.username = username;
        this.password = password;
        if (publicAccess) {
            log.warn("PUBLIC ACCESS ENABLED: no website login is required. Saved commute settings "
                    + "and trip history belong to the browser credential; keep browser access private.");
        }
    }

    public boolean enabled() { return enabled; }
    public boolean publicAccess() { return publicAccess; }
    public String username() { return username; }
    public String password() { return password; }
}
