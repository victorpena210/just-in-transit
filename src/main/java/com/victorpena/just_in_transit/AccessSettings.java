package com.victorpena.just_in_transit;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AccessSettings {
    private final boolean enabled;
    private final String username,password;
    public AccessSettings(@Value("${jit.access.enabled:false}") boolean enabled,
                          @Value("${server.address:127.0.0.1}") String address,
                          @Value("${jit.access.username:victor}") String username,
                          @Value("${jit.access.password:}") String password) {
        if(!enabled && !Set.of("127.0.0.1","::1","[::1]").contains(address))
            throw new IllegalStateException("Non-loopback access requires jit.access.enabled=true and a password. Use the phone profile.");
        if(enabled && (username.isBlank() || password.length()<16 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72 || password.isBlank()))
            throw new IllegalStateException("Protected access requires a username and JIT_PASSWORD containing 16–72 characters.");
        this.enabled=enabled;this.username=username;this.password=password;
    }
    public boolean enabled() { return enabled; }
    public String username() { return username; }
    public String password() { return password; }
}
