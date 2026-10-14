package com.victorpena.just_in_transit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@ActiveProfiles("public")
@SpringBootTest(properties = {
    "via.enabled=false",
    "maps.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:public-web;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "server.forward-headers-strategy=framework"
})
@AutoConfigureMockMvc
class PublicAccessWebTests {
    @Autowired MockMvc mvc;
    @Autowired AccessSettings access;
    @Autowired CommuteRepository saved;
    @Autowired Environment environment;

    @BeforeEach void reset() { saved.deleteAll(); }

    private MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder request) {
        return request.secure(true).with(r -> {
            r.setScheme("https");
            r.setServerPort(443);
            return r;
        });
    }

    @Test void publicProfileStartsWithoutWebsitePassword() {
        assertFalse(access.enabled());
        assertTrue(access.publicAccess());
        assertEquals("0.0.0.0", environment.getProperty("server.address"));
        assertEquals("true", environment.getProperty("server.servlet.session.cookie.secure"));
    }

    @Test void homepageAndHistoryOpenWithoutSigningIn() throws Exception {
        mvc.perform(https(get("/")))
                .andExpect(status().isOk())
                .andExpect(unauthenticated())
                .andExpect(content().string(containsString("Where are you going?")))
                .andExpect(content().string(not(containsString("Sign out"))))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow, noarchive"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(https(get("/history"))).andExpect(status().isOk()).andExpect(unauthenticated());
    }

    @Test void oldLoginAddressGoesStraightToHome() throws Exception {
        mvc.perform(https(get("/login"))).andExpect(redirectedUrl("/"));
    }

    @Test void plainHttpStillRedirectsToHttps() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("https://**"));
    }

    @Test void forwardedHttpsFromTrustedProxyDoesNotLoop() throws Exception {
        mvc.perform(get("/").header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "transit.example")
                .header("X-Forwarded-Port", "443"))
                .andExpect(status().isOk()).andExpect(unauthenticated());
    }

    @Test void mutationsStillRejectMissingCsrfToken() throws Exception {
        for (String path : java.util.List.of("/save", "/forget", "/history/clear", "/journey/action")) {
            mvc.perform(https(post(path))).andExpect(status().isForbidden());
        }
        assertEquals(0, saved.count());
    }

    @Test void visitorCanSaveViewAndDeleteSharedCommuteWithCsrf() throws Exception {
        mvc.perform(https(post("/save")).with(csrf())
                .param("origin", "Public test stop").param("destination", "Public destination")
                .param("date", "2030-01-02").param("arriveBy", "09:00")
                .param("walkToStop", "8").param("transferWalk", "3").param("walkToWork", "10")
                .param("boardingBuffer", "3").param("transferBuffer", "5").param("arrivalBuffer", "5")
                .param("destinationStop", "83463"))
                .andExpect(status().isOk()).andExpect(unauthenticated());
        assertEquals(1, saved.count());
        // No session is reused: saved settings are intentionally shared, not per-visitor.
        mvc.perform(https(get("/"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Public test stop")));
        mvc.perform(https(post("/forget")).with(csrf())).andExpect(status().is3xxRedirection());
        assertEquals(0, saved.count());
    }
}
