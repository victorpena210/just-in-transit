package com.victorpena.just_in_transit;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

@Configuration
public class SecurityConfig {
    @Bean SecurityFilterChain security(HttpSecurity http,AccessSettings access,CommuteNotifications notifications,BrowserIdentity browser) throws Exception {
        if(access.enabled()) {
            http.authorizeHttpRequests(r->r.requestMatchers("/login","/app.css","/offline.html","/offline.js","/sw.js","/manifest.webmanifest","/icon.svg","/icon-192.png","/icon-512.png","/error").permitAll().anyRequest().authenticated())
                .formLogin(f->f.loginPage("/login").defaultSuccessUrl("/",true).permitAll())
                .logout(l->l.addLogoutHandler((req,res,auth)->notifications.stop(browser.id())).logoutSuccessUrl("/login?logout").invalidateHttpSession(true).deleteCookies("JSESSIONID","JIT_JOURNEY"));
        } else {
            http.authorizeHttpRequests(r->r.anyRequest().permitAll()).formLogin(f->f.disable());
            if(access.publicAccess()) {
                http.logout(l->l.disable());
                // A crawler hint, NOT an access control. Public visitors still have access.
                http.headers(h->h.addHeaderWriter(new StaticHeadersWriter("X-Robots-Tag", "noindex, nofollow, noarchive")));
            }
        }
        // Hosted public access still uses HTTPS; local loopback development stays on HTTP.
        if(access.enabled() || access.publicAccess()) {
            http.redirectToHttps(org.springframework.security.config.Customizer.withDefaults());
        }
        // CSRF and Spring's cache-control headers remain enabled, including map/journey actions.
        return http.httpBasic(b->b.disable()).requestCache(c->c.disable()).build();
    }
    @Bean UserDetailsService users(AccessSettings access) {
        if(!access.enabled())return new InMemoryUserDetailsManager();
        String encoded=new BCryptPasswordEncoder().encode(access.password());
        return new InMemoryUserDetailsManager(User.withUsername(access.username()).password("{bcrypt}"+encoded).roles("OWNER").build());
    }
}
