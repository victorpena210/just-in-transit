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

@SpringBootTest(properties={"jit.personal.enabled=false","via.enabled=false","spring.datasource.url=jdbc:h2:mem:jit-test;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
class WebTests {
    @Autowired MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean BrowserIdentity browser;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean BrowserCommuteStore saved;
    @BeforeEach void reset() { org.mockito.Mockito.when(browser.id()).thenReturn("test-browser"); saved.deleteAll(); }
    MockHttpServletRequestBuilder commute(String path) {
        return post(path).with(csrf()).param("origin","Test home").param("destination","Test clock-in")
            .param("date","2030-01-02").param("arriveBy","09:00").param("walkToStop","8")
            .param("transferWalk","3").param("walkToWork","10").param("boardingBuffer","3")
            .param("transferBuffer","5").param("arrivalBuffer","5").param("destinationStop","83463");
    }
    @Test void rendersHomepageAndProtectedForm() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(containsString("When do you need to arrive?")))
            .andExpect(content().string(containsString("name=\"_csrf\"")));
    }
    @Test void invalidWalkingTimesDoNotSave() throws Exception {
        mvc.perform(commute("/save").with(request->{request.setParameter("transferWalk","-3");request.setParameter("origin","");return request;}))
            .andExpect(status().isOk()).andExpect(model().attributeHasErrors("commute"));
        assertEquals(0,saved.count());
    }
    @Test void savesLoadsAndDeletesLocalCommute() throws Exception {
        var response=mvc.perform(commute("/save").param("maxBoardingWait","12").param("maxTransferWait","18"))
            .andExpect(redirectedUrl("/")).andExpect(flash().attribute("notice",containsString("Commute saved"))).andReturn();
        mvc.perform(get("/").flashAttrs(response.getFlashMap())).andExpect(status().isOk())
            .andExpect(content().string(containsString("2030-01-02")));
        assertEquals(12,saved.findById(1L).orElseThrow().maxBoardingWait);
        assertEquals(18,saved.findById(1L).orElseThrow().maxTransferWait);
        assertEquals("Test home",saved.findById(1L).orElseThrow().origin);
        mvc.perform(get("/")).andExpect(content().string(containsString("Test home")));
        mvc.perform(post("/forget").with(csrf())).andExpect(status().is3xxRedirection());
        assertEquals(0,saved.count());
    }
    @Test void unparseableNumberRendersErrorsInsteadOfCrashingTheTemplate() throws Exception {
        saved.save(SavedCommute.from(new CommuteForm("Existing home","Work",java.time.LocalDate.of(2030,1,2),java.time.LocalTime.of(9,0),8,3,10,3,5,5,"83463")));
        for(String field:java.util.List.of("walkToStop","maxTransferWait","date","arriveBy")) {
            mvc.perform(commute("/save").with(request->{request.setParameter(field,"not-a-value");return request;}))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("commute",field))
                .andExpect(content().string(containsString("Check these details.")));
            assertEquals("Existing home",saved.findById(1L).orElseThrow().origin);
        }
    }
    @Test void invertedWaitLimitsAreRejectedWithoutSaving() throws Exception {
        mvc.perform(commute("/save").param("maxTransferWait","2"))
            .andExpect(status().isOk()).andExpect(model().attributeHasErrors("commute"))
            .andExpect(content().string(containsString("Maximum waits must be at least")));
        assertEquals(0,saved.count());
    }
    @Test void planDoesNotSilentlySavePersonalDetails() throws Exception {
        mvc.perform(commute("/plan")).andExpect(status().isOk()).andExpect(content().string(containsString("schedule is unavailable")));
        assertEquals(0,saved.count());
    }
    @Test void crossSitePostWithoutTokenIsRejected() throws Exception {
        mvc.perform(post("/forget")).andExpect(status().isForbidden());
    }
    @Test void saveRedirectPreservesSubmittedDateAndPreferencesWithoutResubmitting() throws Exception {
        var result=mvc.perform(commute("/save").param("maxBoardingWait","12").param("maxTransferWait","25")
            .param("trafficAllowance","6").param("preference","safer"))
            .andExpect(redirectedUrl("/")).andReturn();
        var response=mvc.perform(get("/").flashAttrs(result.getFlashMap())).andExpect(status().isOk()).andReturn();
        var f=(CommuteForm)response.getModelAndView().getModel().get("commute");
        assertEquals(java.time.LocalDate.of(2030,1,2),f.date());assertEquals(6,f.trafficAllowance());assertEquals("safer",f.preference());
        mvc.perform(get("/")).andExpect(status().isOk());assertEquals(1,saved.count());
        org.mockito.Mockito.verify(saved,org.mockito.Mockito.times(1)).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }
    @Test void contradictoryWaitWindowsStayInFormAndDoNotSave() throws Exception {
        mvc.perform(commute("/save").param("maxBoardingWait","2").param("maxTransferWait","3"))
            .andExpect(status().isOk()).andExpect(model().attributeHasErrors("commute"))
            .andExpect(content().string(containsString("Maximum waits must be at least")));
        assertEquals(0,saved.count());
    }
    @Test void failedSaveRetainsEntriesAndDisplaysActionableError() throws Exception {
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("simulated save failure"))
            .when(saved).saveAndFlush(org.mockito.ArgumentMatchers.any());
        mvc.perform(commute("/save")).andExpect(status().isOk()).andExpect(model().attributeHasErrors("commute"))
            .andExpect(content().string(containsString("Your commute could not be saved")))
            .andExpect(content().string(containsString("Test home")));
        assertEquals(0,saved.count());
    }

}
