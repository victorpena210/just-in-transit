package com.victorpena.just_in_transit;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class SecurityConfig {
    @Bean SecurityFilterChain security(HttpSecurity http,AccessSettings access) throws Exception {
        if(access.enabled()) {
            http.authorizeHttpRequests(r->r.requestMatchers("/login","/app.css","/offline.html","/offline.js","/sw.js","/error").permitAll().anyRequest().authenticated())
                .formLogin(f->f.loginPage("/login").defaultSuccessUrl("/",true).permitAll())
                .logout(l->l.logoutSuccessUrl("/login?logout").invalidateHttpSession(true).deleteCookies("JSESSIONID","JIT_JOURNEY"))
                .redirectToHttps(org.springframework.security.config.Customizer.withDefaults());
        } else {
            http.authorizeHttpRequests(r->r.anyRequest().permitAll()).formLogin(f->f.disable());
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
