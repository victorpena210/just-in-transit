package com.victorpena.just_in_transit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;import java.util.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties={"via.enabled=false","maps.enabled=false","jit.access.enabled=false","jit.access.allow-public=true","server.address=0.0.0.0",
    "jit.legacy-import-code=owner-test-code-24-characters-long","spring.datasource.url=jdbc:h2:mem:isolation;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
class BrowserIsolationTests {
    @Autowired MockMvc mvc;@Autowired BrowserCommuteRepository settings;@Autowired CommuteRepository legacy;@Autowired TripReportRepository reports;@Autowired JevReviewRepository reviews;
    static final Cookie A=new Cookie(BrowserIdentity.COOKIE,"A".repeat(43)),B=new Cookie(BrowserIdentity.COOKIE,"B".repeat(43));
    static org.springframework.test.web.servlet.request.RequestPostProcessor https(){return r->{r.setSecure(true);r.setScheme("https");r.setServerPort(443);return r;};}
    static String owner(Cookie c){return BrowserIdentity.hash(c.getValue());}
    @BeforeEach void clean(){settings.deleteAll();legacy.deleteAll();reports.deleteAll();reviews.deleteAll();}
    MockHttpServletRequestBuilder save(Cookie cookie,String name){return post("/save").with(https()).cookie(cookie).with(csrf()).param("origin",name).param("destination","Work").param("date","2030-01-02").param("arriveBy","09:00").param("walkToStop","8").param("transferWalk","3").param("walkToWork","10").param("boardingBuffer","3").param("transferBuffer","5").param("arrivalBuffer","5").param("originStop","81799").param("destinationStop","83463");}
    @Test void publicProfileStartsWithoutLoginAndIssuesOpaqueSecureCookie() throws Exception {
        mvc.perform(get("/").with(https()).with(https())).andExpect(status().isOk()).andExpect(cookie().httpOnly(BrowserIdentity.COOKIE,true)).andExpect(cookie().secure(BrowserIdentity.COOKIE,true));
        assertDoesNotThrow(()->new AccessSettings(false,"0.0.0.0","victor","",true));
        assertThrows(IllegalStateException.class,()->new AccessSettings(false,"0.0.0.0","victor","",false));
    }
    @Test void savedSettingsSurviveNewSessionButStaySeparateAndDeleteIsScoped() throws Exception {
        mvc.perform(save(A,"Browser A home")).andExpect(redirectedUrl("/"));
        mvc.perform(get("/").with(https()).cookie(B)).andExpect(content().string(not(containsString("Browser A home"))));
        mvc.perform(save(B,"Browser B home")).andExpect(redirectedUrl("/"));
        mvc.perform(get("/").with(https()).cookie(A)).andExpect(content().string(containsString("Browser A home"))).andExpect(content().string(not(containsString("Browser B home"))));
        mvc.perform(post("/forget").with(https()).cookie(B).with(csrf())).andExpect(redirectedUrl("/?corridor=work"));
        assertTrue(settings.existsById(owner(A)+":1"));assertFalse(settings.existsById(owner(B)+":1"));
    }
    TripReport report(String id,Cookie c){var r=new TripReport();r.id=id;r.owner=owner(c);r.corridor="work";r.travelDate=LocalDate.of(2030,1,2);r.actualArrival=Instant.parse("2030-01-02T15:00:00Z");r.riderNote="private-"+id;r.timingJson="[{\"event\":\"Arrival\",\"source\":\"Scheduled\"}]";return reports.save(r);}
    @Test void historyReadEditDeleteAndCsvAreOwnerScoped() throws Exception {
        report("a",A);report("b",B);
        mvc.perform(get("/history").with(https()).cookie(B)).andExpect(content().string(not(containsString("private-a"))));
        mvc.perform(get("/history.csv").with(https()).cookie(B)).andExpect(content().string(not(containsString("private-a")))).andExpect(content().string(containsString("private-b")));
        mvc.perform(post("/history/note").with(https()).cookie(B).with(csrf()).param("id","a").param("note","changed"));assertEquals("private-a",reports.findById("a").orElseThrow().riderNote);
        mvc.perform(post("/history/delete").with(https()).cookie(B).with(csrf()).param("id","a"));assertTrue(reports.existsById("a"));
        mvc.perform(post("/history/clear").with(https()).cookie(B).with(csrf()));assertTrue(reports.existsById("a"));assertFalse(reports.existsById("b"));
    }
    @Test void legacyDataIsNeverClaimedByFirstVisitorAndRequiresOwnerCode() throws Exception {
        var old=SavedCommute.from(JourneyLogicTests.form());old.origin="Legacy private home";legacy.save(old);
        mvc.perform(get("/").with(https()).cookie(B)).andExpect(content().string(not(containsString("Legacy private home"))));
        mvc.perform(post("/restore").with(https()).cookie(B).with(csrf()).param("code","incorrect"));assertEquals(0,settings.count());
        mvc.perform(post("/restore").with(https()).cookie(A).with(csrf()).param("code","owner-test-code-24-characters-long")).andExpect(redirectedUrl("/"));
        assertEquals(owner(A),legacy.findById(1L).orElseThrow().legacyOwner);assertTrue(settings.existsById(owner(A)+":1"));assertFalse(settings.existsById(owner(B)+":1"));
    }
    @Test void newFormsRequireCsrfAndDisabledIntegrationsRemainUsable() throws Exception {
        for(String path:List.of("/restore","/notifications/stop","/assistant/clear"))mvc.perform(post(path).with(https()).cookie(A)).andExpect(status().isForbidden());
        mvc.perform(get("/notifications").with(https()).cookie(A)).andExpect(jsonPath("$.available").value(false));
        mvc.perform(get("/assistant").with(https()).cookie(A)).andExpect(status().isOk()).andExpect(content().string(containsString("not enabled")));
        mvc.perform(get("/alternatives").with(https()).cookie(A)).andExpect(status().isOk());
        mvc.perform(post("/alternatives").with(https()).cookie(A).with(csrf()).param("date","broken").param("firstWalk","nonsense")).andExpect(status().isOk());
    }
    @Test void csvPreventsFormulaInjection(){assertEquals("\"'=1+1\"",HistoryController.cell("=1+1"));assertEquals("\"a\"\"b\"",HistoryController.cell("a\"b"));}
}
