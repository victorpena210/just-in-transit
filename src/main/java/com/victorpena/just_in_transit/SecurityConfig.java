package com.victorpena.just_in_transit;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

@Configuration
public class SecurityConfig {
    @Bean SecurityFilterChain localSecurity(HttpSecurity http) throws Exception {
        // Single-user, loopback-only application. Keep CSRF protection for saved addresses.
        return http.authorizeHttpRequests(r->r.anyRequest().permitAll())
            .formLogin(f->f.disable()).httpBasic(b->b.disable()).requestCache(c->c.disable()).build();
    }
    @Bean UserDetailsService noLoginUsers() { return new InMemoryUserDetailsManager(); }
}
