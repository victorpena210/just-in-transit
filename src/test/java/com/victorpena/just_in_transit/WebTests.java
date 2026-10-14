package com.victorpena.just_in_transit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties={"via.enabled=false","spring.datasource.url=jdbc:h2:mem:jit-test;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
class WebTests {
    @Autowired MockMvc mvc;
    @Autowired CommuteRepository saved;
    @BeforeEach void reset() { saved.deleteAll(); }
    MockHttpServletRequestBuilder commute(String path) {
        return post(path).with(csrf()).param("origin","Test home").param("destination","Test clock-in")
            .param("date","2030-01-02").param("arriveBy","09:00").param("walkToStop","8")
            .param("transferWalk","3").param("walkToWork","10").param("boardingBuffer","3")
            .param("transferBuffer","5").param("arrivalBuffer","5").param("destinationStop","83463");
    }
    @Test void rendersHomepageAndProtectedForm() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(containsString("Where are you going?")))
            .andExpect(content().string(containsString("name=\"_csrf\"")));
    }
    @Test void invalidWalkingTimesDoNotSave() throws Exception {
        mvc.perform(commute("/save").with(request->{request.setParameter("transferWalk","-3");request.setParameter("origin","");return request;}))
            .andExpect(status().isOk()).andExpect(model().attributeHasErrors("commute"));
        assertEquals(0,saved.count());
    }
    @Test void savesLoadsAndDeletesLocalCommute() throws Exception {
        mvc.perform(commute("/save")).andExpect(status().isOk()).andExpect(content().string(containsString("Commute saved on this computer")));
        assertEquals("Test home",saved.findById(1L).orElseThrow().origin);
        mvc.perform(get("/")).andExpect(content().string(containsString("Test home")));
        mvc.perform(post("/forget").with(csrf())).andExpect(status().is3xxRedirection());
        assertEquals(0,saved.count());
    }
    @Test void planDoesNotSilentlySavePersonalDetails() throws Exception {
        mvc.perform(commute("/plan")).andExpect(status().isOk()).andExpect(content().string(containsString("schedule is unavailable")));
        assertEquals(0,saved.count());
    }
    @Test void crossSitePostWithoutTokenIsRejected() throws Exception {
        mvc.perform(post("/forget")).andExpect(status().isForbidden());
    }
}
